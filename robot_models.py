from datetime import datetime, timezone
from enum import Enum
from typing import Any
from uuid import uuid4

from pydantic import BaseModel, Field


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class RobotCommandType(str, Enum):
    GO_TO_USER = "GO_TO_USER"
    CHECK_USER = "CHECK_USER"
    START_RHYTHM_GAME = "START_RHYTHM_GAME"
    START_PATROL = "START_PATROL"
    SET_MEDICATION_ALARM = "SET_MEDICATION_ALARM"
    CANCEL_MEDICATION_ALARM = "CANCEL_MEDICATION_ALARM"
    STOP_GAME = "STOP_GAME"
    RETURN_TO_BASE = "RETURN_TO_BASE"
    CALL_GUARDIAN = "CALL_GUARDIAN"
    EMERGENCY_STOP = "EMERGENCY_STOP"


class CommandStatus(str, Enum):
    PENDING = "PENDING"
    ACKNOWLEDGED = "ACKNOWLEDGED"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class RobotState(str, Enum):
    IDLE_AT_BASE = "IDLE_AT_BASE"
    MOVING_TO_USER = "MOVING_TO_USER"
    CHECKING_USER = "CHECKING_USER"
    READY_FOR_GAME = "READY_FOR_GAME"
    PLAYING_GAME = "PLAYING_GAME"
    STOPPING_GAME = "STOPPING_GAME"
    PATROLLING = "PATROLLING"
    PATROL_MOVING = "PATROL_MOVING"
    PATROL_CHECKING = "PATROL_CHECKING"
    PATROL_OBSERVING = "PATROL_OBSERVING"
    PATROL_CLEAR = "PATROL_CLEAR"
    FALL_DETECTED = "FALL_DETECTED"
    RETURNING_TO_BASE = "RETURNING_TO_BASE"
    CALLING_GUARDIAN = "CALLING_GUARDIAN"
    ERROR = "ERROR"
    EMERGENCY_STOPPED = "EMERGENCY_STOPPED"
    OFFLINE = "OFFLINE"


class RobotCommandRequest(BaseModel):
    command: RobotCommandType
    location: str | None = None
    robot_id: str = "temi-01"
    hour: int | None = Field(default=None, ge=0, le=23)
    minute: int | None = Field(default=None, ge=0, le=59)


class RobotCommand(BaseModel):
    command_id: str = Field(default_factory=lambda: str(uuid4()))
    robot_id: str = "temi-01"
    command: RobotCommandType
    location: str | None = None
    hour: int | None = None
    minute: int | None = None
    status: CommandStatus = CommandStatus.PENDING
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)
    error: str | None = None


class RobotStatusUpdate(BaseModel):
    robot_id: str = "temi-01"
    state: RobotState
    location: str | None = None
    battery: int | None = Field(default=None, ge=0, le=100)
    active_command_id: str | None = None
    last_completed_command_id: str | None = None
    command_status: CommandStatus | None = None
    last_error: str | None = None
    status_result: dict[str, Any] | None = None


class RobotStatus(BaseModel):
    robot_id: str = "temi-01"
    state: RobotState = RobotState.OFFLINE
    location: str | None = None
    battery: int | None = None
    active_command_id: str | None = None
    last_completed_command_id: str | None = None
    last_error: str | None = None
    status_result: dict[str, Any] | None = None
    last_seen: datetime | None = None
    online: bool = False
