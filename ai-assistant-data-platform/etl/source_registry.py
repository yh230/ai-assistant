"""The supported ai-assistant source tables and their ODS targets.

Design section 7.3 lists exactly seven source tables for V1; ``system_db`` and
``hospital_db.attendance`` are deferred.  The registry is the single contract
shared by the extractor envelope (``target_table``), the consumer topic list
and the sync-task validation, so a task can never point at a source without a
cleaner or at a target the ODS schema does not contain.
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class SourceSpec:
    schema: str
    table: str
    target_table: str
    cleaner: str


SOURCES: tuple[SourceSpec, ...] = (
    SourceSpec("registration_db", "registration", "ods_registration", "registration"),
    SourceSpec(
        "registration_db", "doctor_schedule", "ods_doctor_schedule", "doctor_schedule"
    ),
    SourceSpec("registration_db", "number_source", "ods_number_source", "number_source"),
    SourceSpec("chat_db", "chat_session", "ods_chat_session", "chat_session"),
    SourceSpec("chat_db", "chat_message", "ods_chat_message", "chat_message"),
    SourceSpec("hospital_db", "hospital", "ods_hospital", "hospital"),
    SourceSpec("hospital_db", "department", "ods_department", "department"),
)

SOURCE_TABLES: tuple[str, ...] = tuple(spec.table for spec in SOURCES)

# Table name -> schema.  Source table names are unique across the four
# databases, which is what lets the consumer route an event by table alone.
SOURCE_SCHEMAS: dict[str, str] = {spec.table: spec.schema for spec in SOURCES}

_BY_PAIR: dict[tuple[str, str], SourceSpec] = {
    (spec.schema, spec.table): spec for spec in SOURCES
}
_BY_TABLE: dict[str, SourceSpec] = {spec.table: spec for spec in SOURCES}


def source_for(schema: str | None, table: str | None) -> SourceSpec | None:
    """Return the supported source spec, or ``None`` when it is not in V1."""
    if not schema or not table:
        return None
    return _BY_PAIR.get((schema, table))


def source_by_table(table: str | None) -> SourceSpec | None:
    return _BY_TABLE.get(table) if table else None


def require_source(schema: str, table: str) -> SourceSpec:
    spec = source_for(schema, table)
    if spec is None:
        raise ValueError(f"unsupported source table: {schema}.{table}")
    return spec


def validate_source_target(schema: str, table: str, target_table: str) -> SourceSpec:
    """Validate the ``(schema, table) -> target`` contract of a sync task."""
    spec = require_source(schema, table)
    if target_table != spec.target_table:
        raise ValueError(
            f"target_table must be {spec.target_table} for {schema}.{table}"
        )
    return spec


def target_table_for(table: str, declared: str | None = None) -> str:
    """Resolve the ODS target of an event, rejecting a mismatched declaration.

    Older envelopes (and tests) do not carry ``target_table``; the registry
    value is used in that case.  A declared target that disagrees with the
    registry is a producer/contract error, not a data error.
    """
    spec = source_by_table(table)
    if spec is None:
        raise ValueError(f"unsupported source table: {table}")
    if declared is not None and declared != spec.target_table:
        raise ValueError(
            f"target_table {declared} does not match {spec.target_table} for {table}"
        )
    return spec.target_table


def topic_for(topic_prefix: str, spec: SourceSpec) -> str:
    return f"{topic_prefix}.{spec.schema}.{spec.table}"
