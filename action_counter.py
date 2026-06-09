import time


TARGET_COUNT = None
TIME_LIMIT_SECONDS = 60
COUNT_COOLDOWN_SECONDS = 0.8

ACTION_NAMES = ("LEFT_HAND_UP", "RIGHT_HAND_UP", "CLAP")

LEFT_SHOULDER = 11
RIGHT_SHOULDER = 12
LEFT_WRIST = 15
RIGHT_WRIST = 16


def is_left_hand_up(landmarks) -> bool:
    left_shoulder = landmarks[LEFT_SHOULDER]
    left_wrist = landmarks[LEFT_WRIST]
    return left_wrist.y < left_shoulder.y


def is_right_hand_up(landmarks) -> bool:
    right_shoulder = landmarks[RIGHT_SHOULDER]
    right_wrist = landmarks[RIGHT_WRIST]
    return right_wrist.y < right_shoulder.y


def is_clap(landmarks) -> bool:
    left_wrist = landmarks[LEFT_WRIST]
    right_wrist = landmarks[RIGHT_WRIST]

    wrist_distance_x = abs(left_wrist.x - right_wrist.x)
    wrist_distance_y = abs(left_wrist.y - right_wrist.y)

    return wrist_distance_x < 0.10 and wrist_distance_y < 0.12


def detect_actions(landmarks) -> dict[str, bool]:
    if not landmarks or len(landmarks) <= RIGHT_WRIST:
        return {action: False for action in ACTION_NAMES}

    return {
        "LEFT_HAND_UP": is_left_hand_up(landmarks),
        "RIGHT_HAND_UP": is_right_hand_up(landmarks),
        "CLAP": is_clap(landmarks),
    }


class ActionCounter:
    def __init__(self) -> None:
        self.reset()

    def reset(self) -> dict:
        self.counts = {action: 0 for action in ACTION_NAMES}
        self.action_active = {action: False for action in ACTION_NAMES}
        self.action_armed = {action: False for action in ACTION_NAMES}
        self.last_count_time = {action: 0.0 for action in ACTION_NAMES}
        self.start_time = time.monotonic()
        return self.get_state(current_actions=[])

    def update(self, detections: dict[str, bool]) -> dict:
        current_actions = [action for action, detected in detections.items() if detected]
        now = time.monotonic()

        for action, detected in detections.items():
            if detected and self.action_armed[action] and not self.action_active[action]:
                can_count = now - self.last_count_time[action] >= COUNT_COOLDOWN_SECONDS
                if can_count:
                    self.counts[action] += 1
                    self.last_count_time[action] = now
                self.action_active[action] = True
            elif not detected:
                self.action_active[action] = False
                self.action_armed[action] = True

        return self.get_state(current_actions=current_actions)

    def mark_no_pose(self) -> dict:
        for action in ACTION_NAMES:
            self.action_active[action] = False
        return self.get_state(current_actions=[])

    def get_state(self, current_actions: list[str] | None = None) -> dict:
        elapsed = time.monotonic() - self.start_time
        remaining_time = max(0, int(TIME_LIMIT_SECONDS - elapsed))

        return {
            "current_actions": current_actions or [],
            "counts": dict(self.counts),
            "target_count": TARGET_COUNT,
            "remaining_time": remaining_time,
            "clear": False,
            "time_over": remaining_time <= 0,
        }
