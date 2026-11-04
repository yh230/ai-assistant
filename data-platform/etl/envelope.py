import base64
import json
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from decimal import Decimal
from typing import Any


def _json_default(value: Any) -> Any:
    """Serialise the value types MySQL rows contain but JSON does not.

    Source rows carry ``datetime``/``date``/``time``/``Decimal`` values (and
    ``timedelta`` for MySQL ``TIME`` via PyMySQL); the ODS event is JSON, so
    they are rendered as ISO-8601 strings and plain decimal strings instead of
    failing the publish.
    """
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, time):
        return value.isoformat()
    if isinstance(value, timedelta):
        total = int(value.total_seconds())
        sign = "-" if total < 0 else ""
        total = abs(total)
        return f"{sign}{total // 3600:02d}:{(total % 3600) // 60:02d}:{total % 60:02d}"
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (bytes, bytearray)):
        return base64.b64encode(bytes(value)).decode("ascii")
    raise TypeError(f"value of type {type(value).__name__} is not JSON serialisable")


@dataclass(frozen=True)
class Envelope:
    event_id: str
    event_type: str
    occurred_at: int
    payload: dict[str, Any]

    def to_bytes(self) -> bytes:
        body = {
            "eventId": self.event_id,
            "eventType": self.event_type,
            "occurredAt": self.occurred_at,
            "payload": self.payload,
        }
        return json.dumps(
            body, ensure_ascii=False, separators=(",", ":"), default=_json_default
        ).encode("utf-8")

    @classmethod
    def from_bytes(cls, raw: bytes) -> "Envelope":
        try:
            body = json.loads(raw.decode("utf-8"))
            event_id = body["eventId"]
            event_type = body["eventType"]
            payload = body["payload"]
            if not isinstance(event_id, str) or not isinstance(event_type, str):
                raise TypeError("eventId and eventType must be strings")
            if not isinstance(payload, dict):
                raise TypeError("payload must be a JSON object")
            return cls(
                event_id=event_id,
                event_type=event_type,
                occurred_at=int(body["occurredAt"]),
                payload=payload,
            )
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise ValueError("invalid envelope") from exc
