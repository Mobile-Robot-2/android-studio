"""URFD cam0 MP4 → MediaPipe 33랜드마크 시퀀스(.npz) 추출.

추론(server의 pose_analyzer.py)과 동일한 추출기/모델을 써서 train·inference 분포를 맞춘다.
출력: landmarks/<stem>.npz  { landmarks:(N,33,4), detected:(N,), fps:float }
"""
from pathlib import Path
import argparse

import cv2
import numpy as np
import mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
from tqdm import tqdm

BASE = Path(__file__).resolve().parent
DEFAULT_MODEL = BASE.parent / "models" / "pose_landmarker_lite.task"


def make_landmarker(model_path: Path):
    if not model_path.exists():
        raise FileNotFoundError(
            f"모델 없음: {model_path}\n"
            "pose_landmarker_lite.task 를 models/ 에 두세요."
        )
    base_options = python.BaseOptions(model_asset_path=str(model_path))
    options = vision.PoseLandmarkerOptions(
        base_options=base_options,
        running_mode=vision.RunningMode.VIDEO,
        num_poses=1,
        min_pose_detection_confidence=0.5,
        min_pose_presence_confidence=0.5,
        min_tracking_confidence=0.5,
    )
    return vision.PoseLandmarker.create_from_options(options)


def process_video(path: Path, landmarker) -> tuple[np.ndarray, np.ndarray, float]:
    cap = cv2.VideoCapture(str(path))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    frames, detected = [], []
    idx = 0
    last_ts = -1
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
        ts = int(idx * 1000.0 / fps)
        if ts <= last_ts:
            ts = last_ts + 1
        last_ts = ts

        result = landmarker.detect_for_video(mp_image, ts)
        if result.pose_landmarks:
            lm = result.pose_landmarks[0]
            arr = np.array(
                [[p.x, p.y, p.z, getattr(p, "visibility", 1.0)] for p in lm],
                dtype=np.float32,
            )
            frames.append(arr)
            detected.append(True)
        else:
            frames.append(np.zeros((33, 4), dtype=np.float32))
            detected.append(False)
        idx += 1
    cap.release()

    if not frames:
        return np.zeros((0, 33, 4), np.float32), np.zeros((0,), bool), fps
    return np.stack(frames), np.array(detected, dtype=bool), float(fps)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--videos", default=str(BASE / "urfd"), help="MP4 폴더")
    ap.add_argument("--out", default=str(BASE / "landmarks"), help="출력 폴더")
    ap.add_argument("--model", default=str(DEFAULT_MODEL))
    args = ap.parse_args()

    video_dir = Path(args.videos)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    videos = sorted(video_dir.glob("*.mp4"))
    if not videos:
        raise SystemExit(f"영상 없음: {video_dir} (먼저 download_urfd.sh 실행)")

    # 영상마다 landmarker를 새로 생성한다. VIDEO 모드는 인스턴스별로 timestamp가
    # 단조 증가해야 하는데, 영상이 바뀌면 timestamp가 0으로 리셋되므로 재사용하면
    # "monotonically increasing" 에러가 난다. 영상끼리 추적상태 공유도 불필요.
    for path in tqdm(videos, desc="MediaPipe 추출"):
        out_path = out_dir / f"{path.stem}.npz"
        if out_path.exists():
            continue
        with make_landmarker(Path(args.model)) as landmarker:
            landmarks, detected, fps = process_video(path, landmarker)
        np.savez_compressed(out_path, landmarks=landmarks, detected=detected, fps=fps)

    print(f"완료 -> {out_dir}")


if __name__ == "__main__":
    main()
