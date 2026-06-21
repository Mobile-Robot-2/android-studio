import json
from pathlib import Path
from threading import Lock
from typing import Any

from pydantic import BaseModel, Field


class MedicationLogRequest(BaseModel):
    robot_id: str = "temi-01"
    status: str
    taken_at: str | None = None
    checked_at: str | None = None
    triggered_at: str | None = None
    time: str | None = None
    timestamp: str | None = None
    source: str | None = None

    class Config:
        extra = "allow"


class MedicationStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._lock = Lock()

    def add_log(self, log: MedicationLogRequest) -> dict[str, Any]:
        with self._lock:
            logs = self._read_logs()
            entry = log.dict()
            entry["time"] = self._entry_time(entry)
            logs.append(entry)
            self._write_logs(logs)
            return entry

    def list_logs(self, robot_id: str = "temi-01") -> list[dict[str, Any]]:
        with self._lock:
            logs = self._read_logs()
            filtered = [log for log in logs if log.get("robot_id") == robot_id]
            return sorted(filtered, key=lambda item: item.get("time") or "", reverse=True)

    def _read_logs(self) -> list[dict[str, Any]]:
        if not self.path.exists():
            return []

        try:
            with self.path.open("r", encoding="utf-8") as file:
                data = json.load(file)
        except (json.JSONDecodeError, OSError):
            return []

        if isinstance(data, list):
            return data
        return []

    def _write_logs(self, logs: list[dict[str, Any]]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("w", encoding="utf-8") as file:
            json.dump(logs, file, ensure_ascii=False, indent=2)

    @staticmethod
    def _entry_time(entry: dict[str, Any]) -> str | None:
        return (
            entry.get("time")
            or entry.get("timestamp")
            or entry.get("taken_at")
            or entry.get("checked_at")
            or entry.get("triggered_at")
        )
