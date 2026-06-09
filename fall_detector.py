from collections import deque
import math
import time


NOSE = 0
LEFT_SHOULDER = 11
RIGHT_SHOULDER = 12
LEFT_HIP = 23
RIGHT_HIP = 24

FALL_CONFIRM_SECONDS = 1.2
RECOVERY_CONFIRM_SECONDS = 1.5
RECOVERED_DISPLAY_SECONDS = 2.0
EVIDENCE_GRACE_SECONDS = 0.6

DROP_WINDOW_SECONDS = 1.0
MIN_CENTER_DROP = 0.12
HORIZONTAL_ANGLE_DEGREES = 50.0
STRONG_HORIZONTAL_ANGLE_DEGREES = 75.0
LOW_HIP_Y = 0.55
LOW_SHOULDER_Y = 0.45
BODY_CENTER_LOW_Y = 0.50


class FallDetector:
    """Stateful fall-suspicion detector using normalized pose landmarks."""

    def __init__(self) -> None:
        self.reset()

    def reset(self) -> dict:
        self.status = "NORMAL"
        self.suspected_since = None
        self.recovery_since = None
        self.recovered_since = None
        self.last_evidence_time = 0.0
        self.center_history = deque()
        self.last_metrics = self._empty_metrics()
        return self.get_state()

    def update(self, landmarks) -> dict:
        now = time.monotonic()
        metrics = self._calculate_metrics(landmarks, now)
        self.last_metrics = metrics

        strong_horizontal = (
            metrics["torso_angle"] >= STRONG_HORIZONTAL_ANGLE_DEGREES
        )
        fallen_pose = metrics["horizontal"] and (
            metrics["low_position"] or strong_horizontal
        )
        fall_evidence = fallen_pose or (
            metrics["sudden_drop"] and (metrics["horizontal"] or metrics["low_position"])
        )

        if fall_evidence:
            self.last_evidence_time = now

        if self.status == "NORMAL":
            if fall_evidence:
                self.status = "FALL_SUSPECTED"
                self.suspected_since = now

        elif self.status == "FALL_SUSPECTED":
            if fallen_pose:
                if self.suspected_since is None:
                    self.suspected_since = now
                if now - self.suspected_since >= FALL_CONFIRM_SECONDS:
                    self.status = "FALL_CONFIRMED"
                    self.recovery_since = None
            elif now - self.last_evidence_time > EVIDENCE_GRACE_SECONDS:
                self.status = "NORMAL"
                self.suspected_since = None

        elif self.status == "FALL_CONFIRMED":
            recovered_pose = not metrics["horizontal"] and not metrics["low_position"]
            if recovered_pose:
                if self.recovery_since is None:
                    self.recovery_since = now
                elif now - self.recovery_since >= RECOVERY_CONFIRM_SECONDS:
                    self.status = "RECOVERED"
                    self.recovered_since = now
                    self.suspected_since = None
            else:
                self.recovery_since = None

        elif self.status == "RECOVERED":
            if fall_evidence:
                self.status = "FALL_SUSPECTED"
                self.suspected_since = now
                self.recovered_since = None
            elif (
                self.recovered_since is not None
                and now - self.recovered_since >= RECOVERED_DISPLAY_SECONDS
            ):
                self.status = "NORMAL"
                self.recovered_since = None

        return self.get_state()

    def update_no_pose(self) -> dict:
        self.last_metrics = self._empty_metrics()
        return self.get_state()

    def get_state(self) -> dict:
        return {
            "fall_detected": self.status == "FALL_CONFIRMED",
            "fall_status": self.status,
            "fall_confidence": self._confidence(),
            "fall_metrics": dict(self.last_metrics),
        }

    def _calculate_metrics(self, landmarks, now: float) -> dict:
        nose = landmarks[NOSE]
        left_shoulder = landmarks[LEFT_SHOULDER]
        right_shoulder = landmarks[RIGHT_SHOULDER]
        left_hip = landmarks[LEFT_HIP]
        right_hip = landmarks[RIGHT_HIP]

        shoulder_x = (left_shoulder.x + right_shoulder.x) / 2
        shoulder_y = (left_shoulder.y + right_shoulder.y) / 2
        hip_x = (left_hip.x + right_hip.x) / 2
        hip_y = (left_hip.y + right_hip.y) / 2
        body_center_y = (nose.y + shoulder_y + hip_y) / 3

        torso_dx = abs(shoulder_x - hip_x)
        torso_dy = abs(shoulder_y - hip_y)
        torso_angle = math.degrees(math.atan2(torso_dx, max(torso_dy, 1e-6)))
        horizontal = torso_angle >= HORIZONTAL_ANGLE_DEGREES
        low_position = (
            hip_y >= LOW_HIP_Y
            or shoulder_y >= LOW_SHOULDER_Y
            or body_center_y >= BODY_CENTER_LOW_Y
        )

        self.center_history.append((now, body_center_y))
        while self.center_history and now - self.center_history[0][0] > DROP_WINDOW_SECONDS:
            self.center_history.popleft()

        previous_center_y = min(
            (center_y for _, center_y in self.center_history),
            default=body_center_y,
        )
        center_drop = max(0.0, body_center_y - previous_center_y)
        sudden_drop = center_drop >= MIN_CENTER_DROP

        return {
            "torso_angle": round(torso_angle, 1),
            "center_drop": round(center_drop, 3),
            "body_center_y": round(body_center_y, 3),
            "shoulder_y": round(shoulder_y, 3),
            "hip_y": round(hip_y, 3),
            "horizontal": horizontal,
            "low_position": low_position,
            "sudden_drop": sudden_drop,
        }

    def _confidence(self) -> float:
        score = 0.0
        if self.last_metrics["horizontal"]:
            score += 0.4
        if self.last_metrics["low_position"]:
            score += 0.35
        if self.last_metrics["sudden_drop"]:
            score += 0.25
        if self.status == "FALL_CONFIRMED":
            score = max(score, 0.8)
        return round(min(score, 1.0), 2)

    @staticmethod
    def _empty_metrics() -> dict:
        return {
            "torso_angle": 0.0,
            "center_drop": 0.0,
            "body_center_y": 0.0,
            "shoulder_y": 0.0,
            "hip_y": 0.0,
            "horizontal": False,
            "low_position": False,
            "sudden_drop": False,
        }
