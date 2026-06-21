from pathlib import Path
from threading import Lock

import cv2
import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from action_counter import ActionCounter
from fall_detector import FallDetector
from medication_store import MedicationLogRequest, MedicationStore
from pose_analyzer import MODEL_PATH, PoseAnalyzer

import time
from robot_manager import RobotCommandConflict, RobotManager
from robot_models import RobotCommandRequest, RobotCommandType, RobotStatusUpdate

import firebase_admin
from firebase_admin import credentials, db

cred = credentials.Certificate("firebase-key.json")

firebase_admin.initialize_app(
    cred,
    {
        "databaseURL":
        "https://mobile-robot-2-default-rtdb.firebaseio.com/"
    }
)

app = FastAPI(title="Pose Rhythm Game API")
BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"
CONTROL_PAGE = BASE_DIR / "templates" / "control.html"
EVIDENCE_DIR = BASE_DIR / "evidence"
DATA_DIR = BASE_DIR / "data"
MEDICATION_LOG_PATH = DATA_DIR / "medication_logs.json"

game_start_time = None

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

counter = ActionCounter()
fall_detector = FallDetector()
robot_manager = RobotManager()
medication_store = MedicationStore(MEDICATION_LOG_PATH)
analyzer: PoseAnalyzer | None = None
startup_error: str | None = None
processing_lock = Lock()


@app.get("/")
def root() -> dict:
    return health()


@app.on_event("startup")
def startup() -> None:
    global analyzer, startup_error
    try:
        analyzer = PoseAnalyzer()
        startup_error = None
    except FileNotFoundError as exc:
        analyzer = None
        startup_error = str(exc)


@app.on_event("shutdown")
def shutdown() -> None:
    if analyzer is not None:
        analyzer.close()


@app.get("/health")
def health() -> dict:
    if startup_error:
        return {
            "status": "error",
            "message": startup_error,
            "model_path": str(MODEL_PATH),
        }
    return {"status": "ok"}


@app.get("/control", include_in_schema=False)
def control_page() -> FileResponse:
    return FileResponse(CONTROL_PAGE)


@app.post("/control/start_patrol")
def control_start_patrol(robot_id: str = "temi-01") -> dict:
    request = RobotCommandRequest(
        robot_id=robot_id,
        command=RobotCommandType.START_PATROL,
        location=None,
    )
    return create_robot_command(request)


@app.post("/control/medication_alarm")
def control_medication_alarm(
    hour: int = Form(...),
    minute: int = Form(...),
    robot_id: str = Form("temi-01"),
) -> dict:
    request = RobotCommandRequest(
        robot_id=robot_id,
        command=RobotCommandType.SET_MEDICATION_ALARM,
        hour=hour,
        minute=minute,
    )
    return create_robot_command(request)


@app.post("/control/cancel_medication_alarm")
def control_cancel_medication_alarm(robot_id: str = Form("temi-01")) -> dict:
    request = RobotCommandRequest(
        robot_id=robot_id,
        command=RobotCommandType.CANCEL_MEDICATION_ALARM,
    )
    return create_robot_command(request)


@app.post("/medication/log")
def add_medication_log(log: MedicationLogRequest) -> dict:
    entry = medication_store.add_log(log)
    return {
        "success": True,
        "log": entry,
    }


@app.get("/medication/logs")
def get_medication_logs(robot_id: str = "temi-01") -> dict:
    return {
        "success": True,
        "logs": medication_store.list_logs(robot_id),
    }


@app.post("/robot/command")
def create_robot_command(request: RobotCommandRequest) -> dict:
    if request.command == RobotCommandType.START_PATROL:
        with processing_lock:
            fall_detector.reset()

    try:
        command = robot_manager.create_command(request)
    except RobotCommandConflict as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc

    return {
        "success": True,
        "command": command,
    }


@app.get("/robot/command")
def get_robot_command(
    robot_id: str = "temi-01",
    last_command_id: str | None = None,
) -> dict:
    command = robot_manager.get_command(robot_id, last_command_id)
    return {
        "success": True,
        "has_command": command is not None,
        "command": command,
    }


@app.post("/robot/status")
def update_robot_status(update: RobotStatusUpdate) -> dict:
    status = robot_manager.update_status(update)
    return {
        "success": True,
        "status": status,
    }


@app.get("/robot/status")
def get_robot_status() -> dict:
    return {
        "success": True,
        "status": robot_manager.get_status(),
        "active_command": robot_manager.get_active_command(),
    }


@app.post("/analyze")
async def analyze(
    image: UploadFile | None = File(None),
    file: UploadFile | None = File(None),
    elapsed_time: float | None = Form(None),
) -> dict:
    upload = image or file
    if upload is None:
        raise HTTPException(
            status_code=400,
            detail="Missing image file. Use multipart field name 'image' or 'file'.",
        )
    result = await _analyze_upload(upload, elapsed_time)

    firebase_data = {
        "timestamp": time.time(),
        "counts": result["counts"],
        "elapsed_time": result["elapsed_time"],
        "game_clear": result["game_clear"],
        "fall_detected": result["fall_detected"]
    }
    db.reference("game/current").set(firebase_data)
    db.reference("game/history").push(firebase_data)

    return result


@app.post("/analyze_frame")
async def analyze_frame(
    image: UploadFile = File(...),
    elapsed_time: float | None = Form(None),
) -> dict:
    return await _analyze_upload(image, elapsed_time)


async def _analyze_upload(upload: UploadFile, elapsed_time: float | None = None) -> dict:
    if analyzer is None:
        raise HTTPException(
            status_code=500,
            detail={
                "message": startup_error or "Pose analyzer is not initialized.",
                "model_path": str(MODEL_PATH),
            },
        )

    image_bytes = await upload.read()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="Empty image file.")

    try:
        with processing_lock:
            analysis = analyzer.analyze_image_bytes(image_bytes)
            if analysis["detected"]:
                state = counter.update(analysis["detections"], elapsed_time=elapsed_time)
                fall_state = fall_detector.update(analysis["landmarks"])
            else:
                state = counter.mark_no_pose(elapsed_time=elapsed_time)
                fall_state = fall_detector.update_no_pose()

            fall_event = fall_state.get("fall_event", {})
            if fall_event.get("new_event"):
                evidence_image = _save_fall_evidence_image(
                    image_bytes=image_bytes,
                    event_id=fall_event["event_id"],
                )
                fall_detector.attach_evidence_image(evidence_image)
                fall_state["fall_event"]["evidence_image"] = evidence_image
                fall_detector.mark_event_handled()
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Pose analysis failed: {exc}") from exc

    if game_start_time is not None:
        elapsed_time = round(
            time.time() - game_start_time,
            2
        )


    return {
        "success": True,
        "detected": analysis["detected"],
        "message": None if analysis["detected"] else "No pose detected",
        "detected_action": state["detected_action"],
        "expected_action": state["expected_action"],
        "action_correct": state["action_correct"],
        "action_score": state["action_score"],
        "total_pose_score": state["total_pose_score"],
        "current_actions": state["current_actions"],
        "counts": state["counts"],
        "target_count": state["target_count"],
        "remaining_time": state["remaining_time"],
        "clear": state["clear"],
        "game_clear": state["game_clear"],
        "time_over": state["time_over"],
        **fall_state,
        "elapsed_time": elapsed_time,
    }


def _save_fall_evidence_image(image_bytes: bytes, event_id: str) -> str | None:
    try:
        EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
        image_array = np.frombuffer(image_bytes, dtype=np.uint8)
        frame = cv2.imdecode(image_array, cv2.IMREAD_COLOR)
        if frame is None:
            return None

        filename = f"{event_id}.jpg"
        path = EVIDENCE_DIR / filename
        ok = cv2.imwrite(str(path), frame)
        if not ok:
            return None
        return f"evidence/{filename}"
    except Exception:
        return None


@app.post("/reset")
def reset() -> dict:
    global game_start_time

    # Firebase 이전 게임 데이터 삭제
    db.reference("game/history").delete()
    db.reference("game/current").delete()

    with processing_lock:
        state = counter.reset()
        fall_state = fall_detector.reset()
    game_start_time = time.time()
    print(f"게임 시작: {game_start_time}")
    return {
        "success": True,
        "message": "Game state reset.",
        "current_actions": state["current_actions"],
        "detected_action": state["detected_action"],
        "expected_action": state["expected_action"],
        "action_correct": state["action_correct"],
        "action_score": state["action_score"],
        "total_pose_score": state["total_pose_score"],
        "counts": state["counts"],
        "target_count": state["target_count"],
        "remaining_time": state["remaining_time"],
        "clear": state["clear"],
        "game_clear": state["game_clear"],
        "time_over": state["time_over"],
        **fall_state,
    }


@app.get("/state")
def state() -> dict:
    with processing_lock:
        current_state = counter.get_state()
        fall_state = fall_detector.get_state()
    
    elapsed_time = None

    if game_start_time is not None:
        elapsed_time = round(
            time.time() - game_start_time,
            2
        )
        
    return {
        "success": True,
        "current_actions": current_state["current_actions"],
        "detected_action": current_state["detected_action"],
        "expected_action": current_state["expected_action"],
        "action_correct": current_state["action_correct"],
        "action_score": current_state["action_score"],
        "total_pose_score": current_state["total_pose_score"],
        "counts": current_state["counts"],
        "target_count": current_state["target_count"],
        "remaining_time": current_state["remaining_time"],
        "clear": current_state["clear"],
        "game_clear": current_state["game_clear"],
        "time_over": current_state["time_over"],
        **fall_state,
        "elapsed_time": elapsed_time,
    }
