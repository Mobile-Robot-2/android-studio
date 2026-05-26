from pathlib import Path
import time

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks import python
from mediapipe.tasks.python import vision


BASE_DIR = Path(__file__).resolve().parent
MODEL_PATH = BASE_DIR / "models" / "pose_landmarker_lite.task"

CAMERA_INDEX = 0
TARGET_COUNT = 5
TIME_LIMIT_SECONDS = 60

ACTION_NAMES = ("LEFT_HAND_UP", "RIGHT_HAND_UP", "ARMS_OPEN")

# MediaPipe Pose Landmarker returns 33 normalized pose landmarks.
# Index reference:
# 11 LEFT_SHOULDER, 12 RIGHT_SHOULDER, 13 LEFT_ELBOW, 14 RIGHT_ELBOW,
# 15 LEFT_WRIST, 16 RIGHT_WRIST
LEFT_SHOULDER = 11
RIGHT_SHOULDER = 12
LEFT_ELBOW = 13
RIGHT_ELBOW = 14
LEFT_WRIST = 15
RIGHT_WRIST = 16

ARM_CONNECTIONS = (
    (LEFT_SHOULDER, LEFT_ELBOW),
    (LEFT_ELBOW, LEFT_WRIST),
    (RIGHT_SHOULDER, RIGHT_ELBOW),
    (RIGHT_ELBOW, RIGHT_WRIST),
    (LEFT_SHOULDER, RIGHT_SHOULDER),
)


def is_left_hand_up(landmarks) -> bool:
    left_shoulder = landmarks[LEFT_SHOULDER]
    left_wrist = landmarks[LEFT_WRIST]
    return left_wrist.y < left_shoulder.y


def is_right_hand_up(landmarks) -> bool:
    right_shoulder = landmarks[RIGHT_SHOULDER]
    right_wrist = landmarks[RIGHT_WRIST]
    return right_wrist.y < right_shoulder.y


def is_arms_open(landmarks) -> bool:
    left_shoulder = landmarks[LEFT_SHOULDER]
    right_shoulder = landmarks[RIGHT_SHOULDER]
    left_wrist = landmarks[LEFT_WRIST]
    right_wrist = landmarks[RIGHT_WRIST]

    left_open = left_wrist.x < left_shoulder.x
    right_open = right_wrist.x > right_shoulder.x

    shoulder_y_avg = (left_shoulder.y + right_shoulder.y) / 2
    left_wrist_near_shoulder = abs(left_wrist.y - shoulder_y_avg) < 0.25
    right_wrist_near_shoulder = abs(right_wrist.y - shoulder_y_avg) < 0.25

    return left_open and right_open and left_wrist_near_shoulder and right_wrist_near_shoulder


def detect_actions(landmarks) -> dict[str, bool]:
    """Return action decisions from normalized pose landmarks.

    This function is intentionally independent from webcam/OpenCV code so the
    same logic can later run on frames received from a Temi camera pipeline.
    """
    if not landmarks or len(landmarks) <= RIGHT_WRIST:
        return {action: False for action in ACTION_NAMES}

    return {
        "LEFT_HAND_UP": is_left_hand_up(landmarks),
        "RIGHT_HAND_UP": is_right_hand_up(landmarks),
        "ARMS_OPEN": is_arms_open(landmarks),
    }


def update_counts(
    detections: dict[str, bool],
    counts: dict[str, int],
    action_active: dict[str, bool],
) -> list[str]:
    """Count only transitions from inactive to active for each action."""
    newly_detected = []

    for action, detected in detections.items():
        if detected and not action_active[action]:
            if counts[action] < TARGET_COUNT:
                counts[action] += 1
            action_active[action] = True
            newly_detected.append(action)
        elif not detected:
            action_active[action] = False

    return newly_detected


def normalized_to_pixel(landmark, width: int, height: int) -> tuple[int, int]:
    x = int(np.clip(landmark.x, 0.0, 1.0) * width)
    y = int(np.clip(landmark.y, 0.0, 1.0) * height)
    return x, y


def draw_arm_landmarks(frame, landmarks) -> None:
    height, width = frame.shape[:2]

    for start_idx, end_idx in ARM_CONNECTIONS:
        start = normalized_to_pixel(landmarks[start_idx], width, height)
        end = normalized_to_pixel(landmarks[end_idx], width, height)
        cv2.line(frame, start, end, (0, 220, 255), 3)

    for idx in (LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_ELBOW, RIGHT_ELBOW, LEFT_WRIST, RIGHT_WRIST):
        point = normalized_to_pixel(landmarks[idx], width, height)
        cv2.circle(frame, point, 7, (0, 255, 120), -1)
        cv2.circle(frame, point, 9, (20, 20, 20), 2)


def draw_hud(frame, remaining: int, counts: dict[str, int], current_actions: list[str]) -> None:
    lines = [
        f"Time: {remaining}s",
        f"Left Hand Up: {counts['LEFT_HAND_UP']}/{TARGET_COUNT}",
        f"Right Hand Up: {counts['RIGHT_HAND_UP']}/{TARGET_COUNT}",
        f"Arms Open: {counts['ARMS_OPEN']}/{TARGET_COUNT}",
        f"Current: {', '.join(current_actions) if current_actions else 'NONE'}",
    ]

    x, y = 20, 38
    for line in lines:
        cv2.putText(frame, line, (x, y), cv2.FONT_HERSHEY_SIMPLEX, 0.72, (0, 0, 0), 4)
        cv2.putText(frame, line, (x, y), cv2.FONT_HERSHEY_SIMPLEX, 0.72, (255, 255, 255), 2)
        y += 36


def create_pose_landmarker() -> vision.PoseLandmarker:
    if not MODEL_PATH.exists():
        raise FileNotFoundError(
            f"Model file not found: {MODEL_PATH}\n"
            "Download pose_landmarker_lite.task into the models folder first."
        )

    base_options = python.BaseOptions(model_asset_path=str(MODEL_PATH))
    options = vision.PoseLandmarkerOptions(
        base_options=base_options,
        running_mode=vision.RunningMode.VIDEO,
        num_poses=1,
        min_pose_detection_confidence=0.5,
        min_pose_presence_confidence=0.5,
        min_tracking_confidence=0.5,
    )
    return vision.PoseLandmarker.create_from_options(options)


def main() -> None:
    counts = {action: 0 for action in ACTION_NAMES}
    action_active = {action: False for action in ACTION_NAMES}

    start_time = time.monotonic()
    last_timestamp_ms = -1

    cap = None
    with create_pose_landmarker() as landmarker:
        try:
            # If the camera does not open, try CAMERA_INDEX = 1 or close other apps
            # using the webcam. On Windows, CAP_DSHOW often reduces startup delay.
            cap = cv2.VideoCapture(CAMERA_INDEX, cv2.CAP_DSHOW)
            if not cap.isOpened():
                raise RuntimeError(
                    f"Could not open camera index {CAMERA_INDEX}. "
                    "Try CAMERA_INDEX = 1 or check Windows camera permissions."
                )

            while True:
                ok, frame = cap.read()
                if not ok:
                    print("Could not read a frame from the camera.")
                    break

                elapsed = time.monotonic() - start_time
                remaining = max(0, int(TIME_LIMIT_SECONDS - elapsed))

                frame = cv2.flip(frame, 1)

                # MediaPipe Image expects RGB data for SRGB. OpenCV webcam frames
                # arrive as BGR, so this conversion avoids ImageFormat errors and
                # strange color/channel bugs.
                rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)

                # VIDEO mode requires monotonically increasing timestamps in ms.
                # time.monotonic() avoids wall-clock changes that can make Tasks
                # reject a frame with a timestamp_ms error.
                timestamp_ms = int((time.monotonic() - start_time) * 1000)
                if timestamp_ms <= last_timestamp_ms:
                    timestamp_ms = last_timestamp_ms + 1
                last_timestamp_ms = timestamp_ms

                result = landmarker.detect_for_video(mp_image, timestamp_ms)

                current_actions = []
                if result.pose_landmarks:
                    landmarks = result.pose_landmarks[0]
                    detections = detect_actions(landmarks)
                    current_actions = [action for action, detected in detections.items() if detected]
                    update_counts(detections, counts, action_active)
                    draw_arm_landmarks(frame, landmarks)
                else:
                    for action in ACTION_NAMES:
                        action_active[action] = False

                draw_hud(frame, remaining, counts, current_actions)

                if all(count >= TARGET_COUNT for count in counts.values()):
                    cv2.putText(frame, "CLEAR!", (20, 250), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 0, 0), 5)
                    cv2.putText(frame, "CLEAR!", (20, 250), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 255, 0), 3)

                if remaining <= 0:
                    cv2.putText(frame, "TIME OVER", (20, 300), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 0, 0), 5)
                    cv2.putText(frame, "TIME OVER", (20, 300), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 80, 255), 3)

                cv2.imshow("Pose Rhythm Game Prototype", frame)

                if cv2.waitKey(1) & 0xFF == 27:
                    break
        finally:
            if cap is not None:
                cap.release()
            cv2.destroyAllWindows()


if __name__ == "__main__":
    try:
        main()
    except FileNotFoundError as exc:
        print(exc)
        print(
            "Download link: "
            "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
            "pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
        )
    except Exception as exc:
        print(f"Error: {exc}")
        print("Check the model path, camera index, MediaPipe Image format, and timestamp_ms comments above.")
