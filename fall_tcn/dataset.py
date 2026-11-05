"""랜드마크 시퀀스 + URFD 라벨 → 윈도우 학습 데이터셋.

윈도우 라벨: 윈도우의 마지막 프레임 URFD 라벨이 1(누움)이면 낙상=1, 아니면 0.
ADL 시퀀스(adl-*)는 낙상 없음 → 전부 0.
URFD 프레임번호는 1-base, MP4 인덱스는 0-base 로 가정하고 (frame-1) 로 정렬한다.
"""
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from torch.utils.data import Dataset

from features import sequence_features, NUM_FEATURES

BASE = Path(__file__).resolve().parent


def load_frame_labels(csv_path: Path) -> dict[tuple[str, int], int]:
    """urfall-cam0-*.csv → {(seq, frame_idx0): label}. 라벨 {-1,0,1}."""
    if not csv_path.exists():
        return {}
    df = pd.read_csv(csv_path, header=None)
    labels = {}
    for seq, frame, label in zip(df[0], df[1], df[2]):
        labels[(str(seq), int(frame) - 1)] = int(label)  # 1-base → 0-base
    return labels


def _seq_name(stem: str) -> str:
    """'fall-01-cam0' → 'fall-01' (CSV의 시퀀스명과 매칭)."""
    return stem.replace("-cam0", "").replace("-cam1", "")


def build_windows(
    landmarks_dir: Path,
    falls_csv: Path,
    adls_csv: Path,
    window: int = 10,
    stride: int = 5,
    frame_stride: int = 3,
    include_transition: bool = False,
    min_detected_ratio: float = 0.5,
):
    frame_labels = load_frame_labels(falls_csv)
    frame_labels.update(load_frame_labels(adls_csv))

    X, y, groups = [], [], []
    for npz_path in sorted(landmarks_dir.glob("*.npz")):
        stem = npz_path.stem
        seq = _seq_name(stem)
        is_adl = stem.startswith("adl")

        data = np.load(npz_path)
        landmarks, detected = data["landmarks"], data["detected"]

        # 30fps 추출본을 배포 fps에 맞춰 다운샘플 (frame_stride=3 → 10fps).
        # 속도 피처가 배포 시점과 같은 시간 간격(100ms)을 반영하도록
        # 피처 계산 '전에' 프레임을 솎는다.
        if frame_stride > 1:
            sel = np.arange(0, landmarks.shape[0], frame_stride)
            landmarks, detected = landmarks[sel], detected[sel]
            orig_frames = sel
        else:
            orig_frames = np.arange(landmarks.shape[0])

        if landmarks.shape[0] < window:
            continue

        feats = sequence_features(landmarks, detected)  # (N, NUM_FEATURES)
        n = feats.shape[0]

        for start in range(0, n - window + 1, stride):
            end = start + window
            win_det = detected[start:end]
            if win_det.mean() < min_detected_ratio:
                continue

            last_idx = int(orig_frames[end - 1])  # URFD 라벨은 원본 프레임 기준
            if is_adl:
                label = 0
            else:
                urfd_label = frame_labels.get((seq, last_idx), -1)
                if urfd_label == 1:
                    label = 1
                elif urfd_label == 0 and not include_transition:
                    continue  # 전이 프레임은 기본 제외(모호)
                else:
                    label = 0

            X.append(feats[start:end])
            y.append(label)
            groups.append(seq)

    if not X:
        raise SystemExit("윈도우가 생성되지 않음. landmarks/ 와 라벨 CSV를 확인하세요.")
    return np.stack(X).astype(np.float32), np.array(y, np.int64), np.array(groups)


def normalize(X: np.ndarray, mean=None, std=None):
    """채널별 표준화. mean/std 미지정이면 X로부터 계산해 반환."""
    if mean is None:
        mean = X.reshape(-1, X.shape[-1]).mean(axis=0)
        std = X.reshape(-1, X.shape[-1]).std(axis=0) + 1e-6
    return (X - mean) / std, mean, std


class WindowDataset(Dataset):
    def __init__(self, X: np.ndarray, y: np.ndarray):
        # TCN 입력은 (B, C, T) → (window, feat) 를 (feat, window) 로 전치
        self.X = torch.from_numpy(X).permute(0, 2, 1).contiguous()
        self.y = torch.from_numpy(y)

    def __len__(self):
        return len(self.y)

    def __getitem__(self, i):
        return self.X[i], self.y[i]


assert NUM_FEATURES  # 피처 수 import 확인용
