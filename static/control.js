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
  medicationHour: document.getElementById("medicationHour"),
  medicationMinute: document.getElementById("medicationMinute"),
  medicationLogs: document.getElementById("medicationLogs"),
};

const movementStates = new Set(["MOVING_TO_USER", "RETURNING_TO_BASE"]);
const busyStates = new Set([
  "MOVING_TO_USER",
  "RETURNING_TO_BASE",
  "CALLING_GUARDIAN",
  "PATROLLING",
  "PATROL_MOVING",
  "PATROL_CHECKING",
  "PATROL_OBSERVING",
]);
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
    const [health, robot, game, medication] = await Promise.all([
      requestJson("/health"),
      requestJson("/robot/status"),
      requestJson("/state"),
      requestJson("/medication/logs?robot_id=temi-01"),
    ]);

    elements.serverStatus.textContent =
      health.status === "ok" ? "정상" : "모델 오류";
    setTone(
      elements.serverStatus,
      health.status === "ok" ? "is-good" : "is-warning",
    );
    updateRobotView(robot);
    updateGameView(game);
    updateMedicationLogs(medication.logs || []);
    elements.lastUpdated.textContent =
      `마지막 갱신 ${new Date().toLocaleTimeString()}`;
  } catch (error) {
    elements.serverStatus.textContent = "연결 실패";
    setTone(elements.serverStatus, "is-danger");
    elements.commandMessage.textContent = error.message;
  }
}

function medicationStatusText(status) {
  if (status === "ALARM_TRIGGERED") {
    return "알람 울림";
  }
  if (status === "TAKEN") {
    return "복약 완료";
  }
  if (status === "NOT_CONFIRMED") {
    return "미확인";
  }
  return status || "-";
}

function updateMedicationLogs(logs) {
  if (!logs.length) {
    elements.medicationLogs.textContent = "기록 없음";
    return;
  }

  elements.medicationLogs.innerHTML = logs.slice(0, 20).map((log) => {
    const time = log.time || log.taken_at || log.checked_at || log.triggered_at || "-";
    const status = medicationStatusText(log.status);
    const source = log.source || "-";
    return `<div class="log-item">${time} / ${status} / ${source}</div>`;
  }).join("");
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
    const url = command === "START_PATROL"
      ? "/control/start_patrol"
      : "/robot/command";
    const options = command === "START_PATROL"
      ? { method: "POST" }
      : {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      };

    const result = await requestJson(url, options);
    elements.commandMessage.textContent =
      `${result.command.command} 명령을 전송했습니다.`;
    hasActiveCommand = true;
    updateButtonAvailability();
    await refreshAll();
  } catch (error) {
    elements.commandMessage.textContent = `명령 실패: ${error.message}`;
  }
}

async function setMedicationAlarm() {
  const hour = Number(elements.medicationHour.value);
  const minute = Number(elements.medicationMinute.value);
  if (!Number.isInteger(hour) || hour < 0 || hour > 23) {
    elements.commandMessage.textContent = "복약 알림 시는 0~23 사이여야 합니다.";
    return;
  }
  if (!Number.isInteger(minute) || minute < 0 || minute > 59) {
    elements.commandMessage.textContent = "복약 알림 분은 0~59 사이여야 합니다.";
    return;
  }

  const form = new FormData();
  form.append("robot_id", "temi-01");
  form.append("hour", String(hour));
  form.append("minute", String(minute));

  elements.commandMessage.textContent = "복약 알림 설정 명령 전송 중";
  try {
    const result = await requestJson("/control/medication_alarm", {
      method: "POST",
      body: form,
    });
    elements.commandMessage.textContent =
      `${result.command.hour}:${String(result.command.minute).padStart(2, "0")} 복약 알림 명령을 전송했습니다.`;
    hasActiveCommand = true;
    updateButtonAvailability();
    await refreshAll();
  } catch (error) {
    elements.commandMessage.textContent = `복약 알림 설정 실패: ${error.message}`;
  }
}

async function cancelMedicationAlarm() {
  const form = new FormData();
  form.append("robot_id", "temi-01");

  elements.commandMessage.textContent = "복약 알림 취소 명령 전송 중";
  try {
    const result = await requestJson("/control/cancel_medication_alarm", {
      method: "POST",
      body: form,
    });
    elements.commandMessage.textContent =
      `${result.command.command} 명령을 전송했습니다.`;
    hasActiveCommand = true;
    updateButtonAvailability();
    await refreshAll();
  } catch (error) {
    elements.commandMessage.textContent = `복약 알림 취소 실패: ${error.message}`;
  }
}

document.querySelectorAll("[data-command]").forEach((button) => {
  button.addEventListener("click", () => sendCommand(button.dataset.command));
});

document.getElementById("refreshButton").addEventListener("click", refreshAll);
document.getElementById("setMedicationButton").addEventListener("click", setMedicationAlarm);
document.getElementById("cancelMedicationButton").addEventListener("click", cancelMedicationAlarm);

refreshAll();
setInterval(refreshAll, 1000);
