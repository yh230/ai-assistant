import re
from dataclasses import dataclass
from typing import Any, Callable

# Per-table projections.  The ODS tables are typed, so each cleaner returns an
# explicit field list: unknown source columns are dropped instead of leaking
# into the warehouse, and PII columns are replaced by masked values.
# Source columns without a projection are intentionally not carried over.


@dataclass(frozen=True)
class CleanResult:
    row: dict[str, Any] | None
    errors: tuple[str, ...] = ()


def mask_name(value: str | None) -> str:
    if not value:
        return ""
    text = str(value)
    return text[0] + "*" * max(len(text) - 1, 1)


def mask_phone(value: str | None) -> str:
    digits = re.sub(r"\D", "", "" if value is None else str(value))
    if len(digits) < 7:
        return "*" * len(digits)
    return digits[:3] + "*" * (len(digits) - 7) + digits[-4:]


def clean_registration(row: dict[str, Any]) -> CleanResult:
    errors: list[str] = []
    try:
        entity_id = int(row["id"])
        hospital_id = int(row["hospital_id"])
    except (KeyError, TypeError, ValueError):
        return CleanResult(None, ("id and hospital_id are required",))
    if not row.get("schedule_date"):
        errors.append("schedule_date is required")
    status = int(row.get("status") or 0)
    if status not in (1, 2, 3, 4, 5, 6):
        errors.append(f"status out of range: {status}")
    period = int(row.get("period") or 0)
    if period not in (1, 2):
        errors.append(f"period invalid: {period}")
    try:
        pay_amount = float(row.get("pay_amount") or 0)
    except (TypeError, ValueError):
        errors.append("pay_amount must be a number")
        pay_amount = 0.0
    if pay_amount < 0:
        errors.append("pay_amount must be >= 0")
    if errors:
        return CleanResult(None, tuple(errors))
    cleaned = {
        "id": entity_id,
        "registration_no": str(row.get("registration_no", "")),
        "hospital_id": hospital_id,
        "department_id": int(row.get("department_id") or 0),
        "doctor_user_id": int(row.get("doctor_user_id") or 0),
        "schedule_date": row["schedule_date"],
        "period": period,
        "patient_name_masked": mask_name(row.get("patient_name")),
        "patient_phone_masked": mask_phone(row.get("patient_phone")),
        "source_type": int(row.get("source_type") or 0),
        "pay_status": int(row.get("pay_status") or 0),
        "pay_amount": pay_amount,
        "status": status,
        "create_time": row.get("create_time"),
        "update_time": row.get("update_time"),
    }
    return CleanResult(cleaned)


def clean_chat_message(row: dict[str, Any]) -> CleanResult:
    content = row.get("content") or ""
    role = row.get("role")
    if role not in ("user", "assistant"):
        return CleanResult(None, (f"invalid role: {role}",))
    try:
        message_id = int(row["id"])
    except (KeyError, TypeError, ValueError):
        return CleanResult(None, ("id is required",))
    # Chat content is PII: only the length and the reference flag are kept.
    return CleanResult({
        "id": message_id,
        "session_id": str(row.get("session_id", "")),
        "role": role,
        "content_length": len(content),
        "has_reference": bool(row.get("reference_json")),
        "created_at": row.get("created_at"),
    })


def _pick(row: dict[str, Any], fields: tuple[str, ...]) -> CleanResult:
    if "id" not in row:
        return CleanResult(None, ("id is required",))
    return CleanResult({field: row.get(field) for field in fields})


def clean_chat_session(row: dict[str, Any]) -> CleanResult:
    return _pick(
        row,
        (
            "id",
            "session_id",
            "user_id",
            "identity",
            "session_type",
            "hospital_id",
            "role_id",
            "created_at",
            "updated_at",
        ),
    )


def clean_doctor_schedule(row: dict[str, Any]) -> CleanResult:
    return _pick(
        row,
        (
            "id",
            "hospital_id",
            "department_id",
            "doctor_user_id",
            "schedule_date",
            "period",
            "total_limit",
            "registered_count",
            "status",
            "update_time",
        ),
    )


def clean_number_source(row: dict[str, Any]) -> CleanResult:
    return _pick(
        row,
        (
            "id",
            "hospital_id",
            "department_id",
            "doctor_user_id",
            "schedule_id",
            "schedule_date",
            "period",
            "seq_no",
            "registration_id",
            "status",
            "create_time",
        ),
    )


def clean_hospital(row: dict[str, Any]) -> CleanResult:
    return _pick(row, ("id", "name", "address", "phone", "status", "update_time"))


def clean_department(row: dict[str, Any]) -> CleanResult:
    return _pick(
        row, ("id", "hospital_id", "name", "director_user_id", "status", "update_time")
    )


_CLEANERS: dict[str, Callable[[dict[str, Any]], CleanResult]] = {
    "registration": clean_registration,
    "chat_message": clean_chat_message,
    "chat_session": clean_chat_session,
    "doctor_schedule": clean_doctor_schedule,
    "number_source": clean_number_source,
    "hospital": clean_hospital,
    "department": clean_department,
}


def clean_row(table: str, row: dict[str, Any]) -> CleanResult:
    cleaner = _CLEANERS.get(table)
    if cleaner is None:
        return CleanResult(None, (f"unknown source table: {table}",))
    if not isinstance(row, dict):
        return CleanResult(None, ("row must be a JSON object",))
    try:
        return cleaner(row)
    except (KeyError, TypeError, ValueError) as exc:
        # Conversion failures are data problems, not pipeline failures: they
        # belong in ops_quarantine rather than the dead-letter topic.
        return CleanResult(None, (f"invalid {table} row: {exc}",))
