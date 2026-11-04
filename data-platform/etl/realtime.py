import json
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from etl.ports.event_bus import ConsumedEvent
from etl.ports.olap_store import OlapStore

REGISTRATION_EVENT_TOPIC = "ai.registration.event"
REGISTRATION_EVENT_TABLE = "ops_registration_event"

# Same convention as the ODS ``_ingested_at`` values in etl.consumer: convert
# to Asia/Shanghai and drop the tzinfo.  Kept local so etl.consumer can import
# the topic constant without a circular import.
try:
    _SHANGHAI_TZ: timezone | ZoneInfo = ZoneInfo("Asia/Shanghai")
except ZoneInfoNotFoundError:  # Windows without the tzdata package
    _SHANGHAI_TZ = timezone(timedelta(hours=8), "Asia/Shanghai")


def _from_epoch_millis(value: int) -> datetime:
    try:
        return datetime.fromtimestamp(value / 1000, _SHANGHAI_TZ).replace(tzinfo=None)
    except (OverflowError, OSError, ValueError) as exc:
        raise ValueError("occurredAt is out of range") from exc


def _now() -> datetime:
    return datetime.now(_SHANGHAI_TZ).replace(tzinfo=None)


def _required_text(value: Any, field: str) -> str:
    if value is None or str(value) == "":
        raise ValueError(f"{field} is required")
    return str(value)


def _required_int(value: Any, field: str) -> int:
    if value is None or value == "":
        raise ValueError(f"{field} is required")
    try:
        return int(value)
    except (TypeError, ValueError):
        raise ValueError(f"{field} must be an integer") from None


def _optional_int(value: Any, field: str) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        raise ValueError(f"{field} must be an integer") from None


def _optional_text(value: Any) -> str:
    return "" if value is None else str(value)


def _optional_date(value: Any) -> date | None:
    # Malformed or missing visit dates are tolerated: the event still feeds the
    # hospital/type counters, and the warehouse column is expected to be
    # nullable (see Task 6 DDL).
    if value is None or value == "":
        return None
    try:
        return date.fromisoformat(str(value)[:10])
    except ValueError:
        return None


@dataclass(frozen=True)
class RegistrationEvent:
    """Decoded ``ai.registration.event`` message.

    The envelope's string ``payload`` (Java ``RegistrationMqEvent``) supplies
    the registration number, patient/hospital ids, doctor and department names,
    visit date, period and reason; ``event_type`` and ``occurred_at`` come from
    the outer envelope.
    """

    event_id: str
    event_type: str
    occurred_at: datetime
    registration_no: str
    patient_id: int | None
    hospital_id: int
    doctor_name: str
    department_name: str
    visit_date: date | None
    period: int | None
    reason: str


@dataclass(frozen=True)
class RegistrationEventResult:
    """What :meth:`RegistrationEventHandler.handle` wrote, for callers/tests."""

    event_id: str
    event_type: str
    table: str
    row: dict[str, Any]


class RegistrationEventHandler:
    """Loads real-time registration events into ``ops_registration_event``.

    The row is deliberately narrow: patient id, doctor/department names and the
    reason stay out of the warehouse (design §2.2/§9), only the event identity,
    hospital, visit date and period needed for the real-time counters are kept.
    Task 6 owns the matching DDL.
    """

    def __init__(self, olap: OlapStore):
        self.olap = olap

    def parse(self, raw: bytes) -> RegistrationEvent:
        """Decode the ``MqEnvelope`` and its inner payload JSON string.

        The ai-assistant publisher serialises ``RegistrationMqEvent`` into the
        envelope's ``payload`` string; a JSON object is accepted as well so
        fixtures and replays do not need the double encoding.
        """
        try:
            body = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError("registration event is not valid JSON") from exc
        if not isinstance(body, dict):
            raise ValueError("registration event envelope must be a JSON object")
        event_id = _required_text(body.get("eventId"), "eventId")
        event_type = _required_text(body.get("eventType"), "eventType")
        occurred_at = _from_epoch_millis(_required_int(body.get("occurredAt"), "occurredAt"))
        payload = body.get("payload")
        if isinstance(payload, str):
            try:
                payload = json.loads(payload)
            except json.JSONDecodeError as exc:
                raise ValueError("registration event payload is not valid JSON") from exc
        if not isinstance(payload, dict):
            raise ValueError("registration event payload must be a JSON object")
        return RegistrationEvent(
            event_id=event_id,
            event_type=event_type,
            occurred_at=occurred_at,
            registration_no=_required_text(payload.get("registrationNo"), "registrationNo"),
            patient_id=_optional_int(payload.get("patientId"), "patientId"),
            hospital_id=_required_int(payload.get("hospitalId"), "hospitalId"),
            doctor_name=_optional_text(payload.get("doctorName")),
            department_name=_optional_text(payload.get("departmentName")),
            visit_date=_optional_date(payload.get("visitDate")),
            period=_optional_int(payload.get("period"), "period"),
            reason=_optional_text(payload.get("reason")),
        )

    def handle(self, event: ConsumedEvent) -> RegistrationEventResult:
        parsed = self.parse(event.value)
        row = {
            "_event_id": parsed.event_id,
            "event_type": parsed.event_type,
            "registration_no": parsed.registration_no,
            "hospital_id": parsed.hospital_id,
            "visit_date": parsed.visit_date,
            "period": parsed.period,
            "occurred_at": parsed.occurred_at,
            "_ingested_at": _now(),
        }
        self.olap.insert_json(REGISTRATION_EVENT_TABLE, [row])
        return RegistrationEventResult(
            event_id=parsed.event_id,
            event_type=parsed.event_type,
            table=REGISTRATION_EVENT_TABLE,
            row=row,
        )
