const elements = {
  serverStatus: document.getElementById("serverStatus"),
  robotOnline: document.getElementById("robotOnline"),
  robotState: document.getElementById("robotState"),
  robotLocation: document.getElementById("robotLocation"),
  robotBattery: document.getElementById("robotBattery"),
  lastSeen: document.getElementById("lastSeen"),
  activeCommandId: document.getElementById("activeCommandId"),
  lastCompletedCommandId: document.getElementById("lastCompletedCommandId"),
  lastUpdated: document.getElementById("lastUpdated"),
  personDetected: document.getElementById("personDetected"),
  fallStatus: document.getElementById("fallStatus"),
  fallConfidence: document.getElementById("fallConfidence"),
  remainingTime: document.getElementById("remainingTime"),
  leftCount: document.getElementById("leftCount"),
  rightCount: document.getElementById("rightCount"),
  armsOpenCount: document.getElementById("armsOpenCount"),
  clapCount: document.getElementById("clapCount"),
  currentActions: document.getElementById("currentActions"),
  gameClear: document.getElementById("gameClear"),
  lastError: document.getElementById("lastError"),
  commandMessage: document.getElementById("commandMessage"),
  userLocation: document.getElementById("userLocation"),
  baseLocation: document.getElementById("baseLocation"),
};

const movementStates = new Set(["MOVING_TO_USER", "RETURNING_TO_BASE"]);
const busyStates = new Set(["MOVING_TO_USER", "RETURNING_TO_BASE", "CALLING_GUARDIAN"]);
let latestRobotState = "OFFLINE";
let hasActiveCommand = false;

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  const body = await response.json();
  if (!response.ok) {
    const detail = typeof body.detail === "string"
      ? body.detail
      : JSON.stringify(body.detail || body);
    throw new Error(detail);
  }
  return body;
}

function setTone(element, tone) {
  element.classList.remove("is-good", "is-warning", "is-danger");
  if (tone) {
    element.classList.add(tone);
  }
}

function updateRobotView(payload) {
  const status = payload.status || {};
  const activeCommand = payload.active_command;
  latestRobotState = status.state || "OFFLINE";
  hasActiveCommand = Boolean(activeCommand);

  elements.robotOnline.textContent = status.online ? "온라인" : "오프라인";
  setTone(elements.robotOnline, status.online ? "is-good" : "is-danger");
  elements.robotState.textContent = latestRobotState;
  elements.robotLocation.textContent = status.location || "-";
  elements.robotBattery.textContent =
    status.battery === null || status.battery === undefined
      ? "-"
      : `${status.battery}%`;
  elements.lastSeen.textContent = status.last_seen
    ? new Date(status.last_seen).toLocaleTimeString()
    : "-";
  elements.activeCommandId.textContent = status.active_command_id || "-";
  elements.lastCompletedCommandId.textContent = status.last_completed_command_id || "-";
  elements.lastError.textContent = status.last_error || "없음";

  const result = status.status_result || {};
  if (Object.keys(result).length > 0) {
    elements.personDetected.textContent = result.detected ? "감지됨" : "감지 안 됨";
    elements.fallStatus.textContent = result.fall_status || "-";
    elements.fallConfidence.textContent =
      result.fall_confidence === undefined ? "-" : result.fall_confidence;
  }

  updateButtonAvailability();
}

function updateGameView(payload) {
  const counts = payload.counts || {};
  elements.leftCount.textContent = counts.LEFT_HAND_UP || 0;
  elements.rightCount.textContent = counts.RIGHT_HAND_UP || 0;
  elements.armsOpenCount.textContent = counts.ARMS_OPEN || 0;
  elements.clapCount.textContent = counts.CLAP || 0;
  elements.remainingTime.textContent =
    payload.remaining_time === undefined ? "-" : `${payload.remaining_time}초`;
  elements.currentActions.textContent =
    payload.current_actions && payload.current_actions.length
      ? payload.current_actions.join(", ")
      : "없음";
  elements.gameClear.textContent = payload.clear ? "성공" : "진행 중";

  if (payload.fall_status) {
    elements.fallStatus.textContent = payload.fall_status;
    elements.fallConfidence.textContent = payload.fall_confidence;
  }
}

function updateButtonAvailability() {
  document.querySelectorAll("[data-command]").forEach((button) => {
    const command = button.dataset.command;
    const isSafetyCommand =
      command === "EMERGENCY_STOP" || command === "STOP_GAME";
    const isMovementCommand =
      command === "GO_TO_USER" || command === "RETURN_TO_BASE";

    button.disabled =
      !isSafetyCommand
      && (
        latestRobotState === "OFFLINE"
        || busyStates.has(latestRobotState)
        || (movementStates.has(latestRobotState) && isMovementCommand)
        || hasActiveCommand
      );
  });
}

async function refreshAll() {
  try {
    const [health, robot, game] = await Promise.all([
      requestJson("/health"),
      requestJson("/robot/status"),
      requestJson("/state"),
    ]);

    elements.serverStatus.textContent =
      health.status === "ok" ? "정상" : "모델 오류";
    setTone(
      elements.serverStatus,
      health.status === "ok" ? "is-good" : "is-warning",
    );
    updateRobotView(robot);
    updateGameView(game);
    elements.lastUpdated.textContent =
      `마지막 갱신 ${new Date().toLocaleTimeString()}`;
  } catch (error) {
    elements.serverStatus.textContent = "연결 실패";
    setTone(elements.serverStatus, "is-danger");
    elements.commandMessage.textContent = error.message;
  }
}

async function sendCommand(command) {
  const payload = { command, robot_id: "temi-01" };
  if (command === "GO_TO_USER") {
    payload.location = elements.userLocation.value.trim();
  } else if (command === "RETURN_TO_BASE") {
    payload.location = elements.baseLocation.value.trim();
  }

  elements.commandMessage.textContent = "명령 전송 중";

  try {
    const result = await requestJson("/robot/command", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    elements.commandMessage.textContent =
      `${result.command.command} 명령을 전송했습니다.`;
    hasActiveCommand = true;
    updateButtonAvailability();
    await refreshAll();
  } catch (error) {
    elements.commandMessage.textContent = `명령 실패: ${error.message}`;
  }
}

document.querySelectorAll("[data-command]").forEach((button) => {
  button.addEventListener("click", () => sendCommand(button.dataset.command));
});

document.getElementById("refreshButton").addEventListener("click", refreshAll);

refreshAll();
setInterval(refreshAll, 1000);
