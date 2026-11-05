"""PII redaction applied to a source row before it leaves the extractor.

The transport topic and the dead-letter topic must never contain raw personal
data (design section 2.2 / 9).  Registration rows lose the patient name,
phone and id-card columns and carry masked derivatives instead; chat messages
lose the message body and reference payload and carry only derived metadata.
Every other table is passed through unchanged because it has no PII
projection in V1.

The cleaners accept the precomputed ``*_masked`` / ``*_length`` fields, so a
redacted row can flow through the pipeline (or a quarantine replay) without
the mask being recomputed from an already-removed source value.
"""

from typing import Any

from etl.cleaning import mask_name, mask_phone

REGISTRATION_DIRECT_PII = ("patient_name", "patient_phone", "patient_id_card")
CHAT_DIRECT_PII = ("content", "reference_json")


def redact_source_row(table: str, row: Any) -> Any:
    """Return a PII-free copy of one source row for the ODS transport.

    Non-dict values are returned unchanged: the cleaner rejects them and the
    consumer's quarantine sample stores only their type name.
    """
    if not isinstance(row, dict):
        return row
    redacted = dict(row)
    if table == "registration":
        name = redacted.pop("patient_name", None)
        phone = redacted.pop("patient_phone", None)
        redacted.pop("patient_id_card", None)
        if redacted.get("patient_name_masked") is None:
            redacted["patient_name_masked"] = mask_name(name)
        if redacted.get("patient_phone_masked") is None:
            redacted["patient_phone_masked"] = mask_phone(phone)
    elif table == "chat_message":
        content = redacted.pop("content", None)
        reference = redacted.pop("reference_json", None)
        if redacted.get("content_length") is None:
            redacted["content_length"] = len(content or "")
        if redacted.get("has_reference") is None:
            redacted["has_reference"] = bool(reference)
    return redacted
