from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware

from action_counter import ActionCounter
from pose_analyzer import MODEL_PATH, PoseAnalyzer


app = FastAPI(title="Pose Rhythm Game API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

counter = ActionCounter()
analyzer: PoseAnalyzer | None = None
startup_error: str | None = None


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


@app.post("/analyze")
async def analyze(file: UploadFile = File(...)) -> dict:
    if analyzer is None:
        raise HTTPException(
            status_code=500,
            detail={
                "message": startup_error or "Pose analyzer is not initialized.",
                "model_path": str(MODEL_PATH),
            },
        )

    image_bytes = await file.read()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="Empty image file.")

    try:
        analysis = analyzer.analyze_image_bytes(image_bytes)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Pose analysis failed: {exc}") from exc

    if analysis["detected"]:
        state = counter.update(analysis["detections"])
    else:
        state = counter.mark_no_pose()

    return {
        "success": True,
        "detected": analysis["detected"],
        "current_actions": state["current_actions"],
        "counts": state["counts"],
        "target_count": state["target_count"],
        "remaining_time": state["remaining_time"],
        "clear": state["clear"],
        "time_over": state["time_over"],
    }


@app.post("/reset")
def reset() -> dict:
    state = counter.reset()
    return {
        "success": True,
        "message": "Game state reset.",
        "current_actions": state["current_actions"],
        "counts": state["counts"],
        "target_count": state["target_count"],
        "remaining_time": state["remaining_time"],
        "clear": state["clear"],
        "time_over": state["time_over"],
    }


@app.get("/state")
def state() -> dict:
    current_state = counter.get_state()
    return {
        "success": True,
        "current_actions": current_state["current_actions"],
        "counts": current_state["counts"],
        "target_count": current_state["target_count"],
        "remaining_time": current_state["remaining_time"],
        "clear": current_state["clear"],
        "time_over": current_state["time_over"],
    }
