"""MediaPipe 33랜드마크 → 낙상 판정용 프레임 피처 벡터.

기존 fall_detector.py 의 기하 계산과 동일한 정의를 학습용 피처로 재사용한다.
랜드마크 배열 형식: (33, 4) = [x, y, z, visibility], y는 아래로 갈수록 커지는 정규화 좌표.
"""
import numpy as np

NOSE = 0
LEFT_SHOULDER = 11
RIGHT_SHOULDER = 12
LEFT_HIP = 23
RIGHT_HIP = 24

KEY_JOINTS = (NOSE, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP, RIGHT_HIP)

FEATURE_NAMES = (
    "com_y",          # 상체 중심 높이 (클수록 낮은 자세)
    "shoulder_y",
    "hip_y",
    "nose_y",
    "torso_angle",    # 몸통이 수평에 가까운 정도 (0=수직, 1=수평)
    "upper_angle",    # 상체(코-어깨)가 수평에 가까운 정도
    "visibility",     # 핵심 관절 평균 신뢰도
)
NUM_BASE_FEATURES = len(FEATURE_NAMES)


def frame_features(landmarks: np.ndarray) -> np.ndarray:
    """(33,4) 랜드마크 → (NUM_BASE_FEATURES,) 피처 벡터."""
    nose = landmarks[NOSE]
    lsh, rsh = landmarks[LEFT_SHOULDER], landmarks[RIGHT_SHOULDER]
    lhip, rhip = landmarks[LEFT_HIP], landmarks[RIGHT_HIP]

    shoulder_x = (lsh[0] + rsh[0]) / 2.0
    shoulder_y = (lsh[1] + rsh[1]) / 2.0
    hip_x = (lhip[0] + rhip[0]) / 2.0
    hip_y = (lhip[1] + rhip[1]) / 2.0
    nose_y = nose[1]
    com_y = (nose_y + shoulder_y + hip_y) / 3.0

    torso_dx = abs(shoulder_x - hip_x)
    torso_dy = abs(shoulder_y - hip_y)
    torso_angle = np.degrees(np.arctan2(torso_dx, max(torso_dy, 1e-6))) / 90.0

    upper_dx = abs(nose[0] - shoulder_x)
    upper_dy = abs(nose_y - shoulder_y)
    upper_angle = np.degrees(np.arctan2(upper_dx, max(upper_dy, 1e-6))) / 90.0

    visibility = float(np.mean([landmarks[j][3] for j in KEY_JOINTS]))

    return np.array(
        [com_y, shoulder_y, hip_y, nose_y, torso_angle, upper_angle, visibility],
        dtype=np.float32,
    )


def sequence_features(landmarks_seq: np.ndarray, detected: np.ndarray) -> np.ndarray:
    """(N,33,4) → (N, NUM_BASE_FEATURES+1). 마지막 채널은 COM y 속도(프레임 간 변화)."""
    n = landmarks_seq.shape[0]
    base = np.zeros((n, NUM_BASE_FEATURES), dtype=np.float32)
    for i in range(n):
        if detected[i]:
            base[i] = frame_features(landmarks_seq[i])
        # 미검출 프레임은 0 유지 (visibility=0 이 신호로 작동)

    com_y = base[:, 0]
    velocity = np.zeros((n, 1), dtype=np.float32)
    if n > 1:
        velocity[1:, 0] = np.diff(com_y)  # 양수=하강(낙하 신호)
    return np.concatenate([base, velocity], axis=1)


NUM_FEATURES = NUM_BASE_FEATURES + 1  # +velocity
