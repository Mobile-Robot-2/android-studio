"""TCN 기반 낙상 판정기 (기존 규칙 방식의 드롭인 교체).

server.py 는 `from fall_detector import FallDetector` 로 가져와서
update / update_no_pose / get_state / reset / attach_evidence_image /
mark_event_handled 만 호출한다. 그 인터페이스와 반환 dict 구조를 그대로 유지하면
server.py 와 안드로이드는 전혀 바뀌지 않는다.

내부 동작:
  - 프레임마다 MediaPipe 랜드마크 → 학습과 동일한 features.py 피처(+속도)로 변환
  - train 시 저장한 평균/표준편차로 정규화 후 최근 window 프레임을 버퍼에 유지
  - 버퍼가 차면 1D-TCN 추론 → 낙상 확률 → 임계값으로 fall_detected 판정

주의:
  - 서버 환경에 `torch` 설치 + fall_tcn/tcn_fall.pt 필요(없으면 import/초기화 실패).
  - 모델은 10fps 로 학습됐으므로, 안드로이드 전송주기(SEND_INTERVAL_MS)가 100ms(=10fps)
    여야 속도 피처의 시간 간격이 맞는다. (안드로이드 변경은 main 브랜치에서 처리)
  - 기존 규칙 방식 구현은 git 히스토리(pose_game 브랜치)에 남아 있다.
"""
from collections import deque
from datetime import datetime
from pathlib import Path
import sys

import numpy as np
import torch

# 학습 파이프라인(fall_tcn)의 피처/모델 정의를 재사용해 train·inference 분포를 맞춘다.
_FALL_TCN_DIR = Path(__file__).resolve().parent / "fall_tcn"
if str(_FALL_TCN_DIR) not in sys.path:
    sys.path.insert(0, str(_FALL_TCN_DIR))

from features import frame_features, NUM_BASE_FEATURES  # noqa: E402
from model import TCN  # noqa: E402

MODEL_PATH = _FALL_TCN_DIR / "tcn_fall.pt"
FALL_THRESHOLD = 0.5  # 낙상 확률 임계값


class FallDetector:
    """윈도우 단위 TCN 추론으로 낙상을 판정하는 stateful 검출기."""

    def __init__(self, model_path: Path = MODEL_PATH, threshold: float = FALL_THRESHOLD) -> None:
        if not Path(model_path).exists():
            raise FileNotFoundError(
                f"낙상 모델 없음: {model_path}\n"
                "fall_tcn/train.py 로 tcn_fall.pt 를 먼저 생성하세요."
            )
        ckpt = torch.load(model_path, map_location="cpu", weights_only=False)
        self._window = int(ckpt["window"])
        self._num_features = int(ckpt["num_features"])
        self._mean = np.asarray(ckpt["mean"], dtype=np.float32)
        self._std = np.asarray(ckpt["std"], dtype=np.float32)
        self._threshold = threshold

        self._model = TCN(num_features=self._num_features)
        self._model.load_state_dict(ckpt["model"])
        self._model.eval()

        self.reset()

    # ─────────────── 공개 인터페이스 (server.py 가 호출) ───────────────

    def reset(self) -> dict:
        self.status = "NORMAL"
        self._buffer: deque = deque(maxlen=self._window)
        self._prev_com_y = None
        self._p_fall = 0.0
        self.last_event = None
        self._new_event = False
        return self.get_state()

    def update(self, landmarks) -> dict:
        arr = self._landmarks_to_array(landmarks)
        base = frame_features(arr)  # (NUM_BASE_FEATURES,)
        self._push(base)
        self._infer_and_update_status()
        return self.get_state()

    def update_no_pose(self) -> dict:
        # 미검출 프레임: 학습 때와 동일하게 0 피처로 채운다(visibility=0 신호).
        self._push(np.zeros(NUM_BASE_FEATURES, dtype=np.float32))
        self._infer_and_update_status()
        return self.get_state()

    def get_state(self) -> dict:
        return {
            "fall_detected": self.status == "FALL_CONFIRMED",
            "fall_status": self.status,
            "fall_confidence": round(float(self._p_fall), 3),
            "fall_metrics": {
                "p_fall": round(float(self._p_fall), 3),
                "buffer_filled": len(self._buffer),
                "window": self._window,
            },
            "fall_event": {
                "new_event": self._new_event,
                "event_id": self.last_event["event_id"] if self.last_event else None,
                "evidence_image": self.last_event.get("evidence_image") if self.last_event else None,
            },
        }

    def attach_evidence_image(self, evidence_image: str | None) -> None:
        if self.last_event is not None:
            self.last_event["evidence_image"] = evidence_image

    def mark_event_handled(self) -> None:
        self._new_event = False

    # ─────────────── 내부 로직 ───────────────

    def _push(self, base: np.ndarray) -> None:
        """base 피처에 속도 채널을 붙이고 정규화해 버퍼에 쌓는다."""
        com_y = float(base[0])
        velocity = 0.0 if self._prev_com_y is None else (com_y - self._prev_com_y)
        self._prev_com_y = com_y
        feat = np.concatenate([base, [velocity]]).astype(np.float32)  # (num_features,)
        feat = (feat - self._mean) / self._std
        self._buffer.append(feat)

    def _infer_and_update_status(self) -> None:
        if len(self._buffer) < self._window:
            self._p_fall = 0.0  # 워밍업: 버퍼가 아직 안 참
        else:
            x = np.stack(self._buffer)  # (window, num_features)
            x = torch.from_numpy(x).permute(1, 0).unsqueeze(0)  # (1, C, T)
            with torch.no_grad():
                logits = self._model(x)
                self._p_fall = float(torch.softmax(logits, dim=1)[0, 1].item())

        fall_now = self._p_fall >= self._threshold
        new_status = "FALL_CONFIRMED" if fall_now else "NORMAL"
        if new_status == "FALL_CONFIRMED" and self.status != "FALL_CONFIRMED":
            self._create_event()
        self.status = new_status

    def _create_event(self) -> None:
        if self.last_event and self.last_event.get("status") == "OPEN":
            return
        event_id = datetime.now().strftime("fall_%Y%m%d_%H%M%S")
        self.last_event = {"event_id": event_id, "evidence_image": None, "status": "OPEN"}
        self._new_event = True

    @staticmethod
    def _landmarks_to_array(landmarks) -> np.ndarray:
        """MediaPipe NormalizedLandmark 리스트 → (33,4) [x,y,z,visibility]."""
        return np.array(
            [[p.x, p.y, p.z, getattr(p, "visibility", 1.0)] for p in landmarks],
            dtype=np.float32,
        )
