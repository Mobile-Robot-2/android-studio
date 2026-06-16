from datetime import datetime, timezone
from threading import Lock

from robot_models import (
    CommandStatus,
    RobotCommand,
    RobotCommandRequest,
    RobotCommandType,
    RobotState,
    RobotStatus,
    RobotStatusUpdate,
)


ROBOT_OFFLINE_SECONDS = 5.0
COMMAND_EXPIRY_SECONDS = 15.0

MOVEMENT_COMMANDS = {
    RobotCommandType.GO_TO_USER,
    RobotCommandType.RETURN_TO_BASE,
}

TERMINAL_COMMAND_STATUSES = {
    CommandStatus.COMPLETED,
    CommandStatus.FAILED,
    CommandStatus.CANCELLED,
}


class RobotCommandConflict(Exception):
    pass


class RobotManager:
    """Thread-safe in-memory command queue and Temi status store."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._commands: dict[str, RobotCommand] = {}
        self._active_command_id: str | None = None
        self._status = RobotStatus()

    def create_command(self, request: RobotCommandRequest) -> RobotCommand:
        with self._lock:
            self._expire_active_command()
            if request.command in MOVEMENT_COMMANDS and not request.location:
                raise RobotCommandConflict(
                    f"Command {request.command.value} requires a location."
                )

            active = self._active_command()
            if active and active.status not in TERMINAL_COMMAND_STATUSES:
                if request.command in {
                    RobotCommandType.EMERGENCY_STOP,
                    RobotCommandType.STOP_GAME,
                    RobotCommandType.RETURN_TO_BASE,
                }:
                    active.status = CommandStatus.CANCELLED
                    active.updated_at = self._now()
                elif (
                    active.command in MOVEMENT_COMMANDS
                    and request.command in MOVEMENT_COMMANDS
                ):
                    raise RobotCommandConflict(
                        "A movement command is already active. "
                        "Stop or complete it before sending another movement command."
                    )
                elif request.command not in {
                    RobotCommandType.STOP_GAME,
                    RobotCommandType.RETURN_TO_BASE,
                }:
                    raise RobotCommandConflict(
                        f"Command {active.command.value} is still active."
                    )

            command = RobotCommand(
                robot_id=request.robot_id,
                command=request.command,
                location=request.location,
            )
            self._commands[command.command_id] = command
            self._active_command_id = command.command_id
            return command

    def get_command(
        self,
        robot_id: str,
        last_command_id: str | None = None,
    ) -> RobotCommand | None:
        with self._lock:
            self._expire_active_command()
            command = self._active_command()
            if command is None or command.robot_id != robot_id:
                return None
            if command.status in TERMINAL_COMMAND_STATUSES:
                return None
            if last_command_id == command.command_id:
                return None
            return command

    def update_status(self, update: RobotStatusUpdate) -> RobotStatus:
        with self._lock:
            now = self._now()
            self._status = RobotStatus(
                robot_id=update.robot_id,
                state=update.state,
                location=update.location,
                battery=update.battery,
                active_command_id=update.active_command_id,
                last_completed_command_id=update.last_completed_command_id,
                last_error=update.last_error,
                status_result=update.status_result,
                last_seen=now,
                online=True,
            )

            if update.active_command_id and update.command_status:
                command = self._commands.get(update.active_command_id)
                if command:
                    command.status = update.command_status
                    command.updated_at = now
                    command.error = update.last_error

                    if update.command_status in TERMINAL_COMMAND_STATUSES:
                        if update.command_status == CommandStatus.COMPLETED:
                            self._status.last_completed_command_id = command.command_id
                        if self._active_command_id == command.command_id:
                            self._active_command_id = None
                        self._status.active_command_id = None

            return self._status_with_online_state()

    def get_status(self) -> RobotStatus:
        with self._lock:
            return self._status_with_online_state()

    def get_active_command(self) -> RobotCommand | None:
        with self._lock:
            self._expire_active_command()
            return self._active_command()

    def _active_command(self) -> RobotCommand | None:
        if self._active_command_id is None:
            return None
        return self._commands.get(self._active_command_id)

    def _expire_active_command(self) -> None:
        command = self._active_command()
        if command is None or command.status != CommandStatus.PENDING:
            return

        age = (self._now() - command.created_at).total_seconds()
        if age > COMMAND_EXPIRY_SECONDS:
            command.status = CommandStatus.CANCELLED
            command.updated_at = self._now()
            command.error = "Command expired before the robot acknowledged it."
            self._active_command_id = None

    def _status_with_online_state(self) -> RobotStatus:
        status = self._copy_model(self._status)
        if status.last_seen is None:
            status.online = False
            status.state = RobotState.OFFLINE
            return status

        age = (self._now() - status.last_seen).total_seconds()
        status.online = age <= ROBOT_OFFLINE_SECONDS
        if not status.online:
            status.state = RobotState.OFFLINE
        return status

    @staticmethod
    def _copy_model(model):
        if hasattr(model, "model_copy"):
            return model.model_copy(deep=True)
        return model.copy(deep=True)

    @staticmethod
    def _now() -> datetime:
        return datetime.now(timezone.utc)
