# tests/unit/test_realtime.py
import json
import unittest
from datetime import date, datetime, timedelta, timezone

from etl.adapters.inmemory_olap_store import InMemoryOlapStore
from etl.ports.event_bus import ConsumedEvent
from etl.realtime import (
    REGISTRATION_EVENT_TABLE,
    REGISTRATION_EVENT_TOPIC,
    RegistrationEventHandler,
)

SHANGHAI = timezone(timedelta(hours=8))
OCCURRED_AT = datetime(2026, 11, 4, 10, 0, 0, tzinfo=SHANGHAI)

ROW_COLUMNS = {
    "_event_id",
    "event_type",
    "registration_no",
    "hospital_id",
    "visit_date",
    "period",
    "occurred_at",
    "_ingested_at",
}


def payload(**overrides) -> dict:
    body = {
        "registrationNo": "R20261104001",
        "patientId": 42,
        "hospitalId": 7,
        "doctorName": "李医生",
        "departmentName": "内科",
        "visitDate": "2026-11-04",
        "period": 1,
        "reason": "复诊",
    }
    body.update(overrides)
    return body


def make_event(
    payload_value=json.dumps(payload(), ensure_ascii=False),
    *,
    event_id: str = "evt-1",
    event_type: str = "APPOINTMENT_CREATED",
    occurred_at: datetime = OCCURRED_AT,
    topic: str = REGISTRATION_EVENT_TOPIC,
) -> ConsumedEvent:
    body = {
        "eventId": event_id,
        "eventType": event_type,
        "occurredAt": int(occurred_at.timestamp() * 1000),
        "payload": payload_value,
    }
    value = json.dumps(body, ensure_ascii=False).encode("utf-8")
    return ConsumedEvent(topic=topic, key=event_id, value=value)


class RegistrationEventHandlerTest(unittest.TestCase):
    def setUp(self):
        self.store = InMemoryOlapStore()
        self.handler = RegistrationEventHandler(self.store)

    def test_constants_match_the_realtime_contract(self):
        self.assertEqual(REGISTRATION_EVENT_TOPIC, "ai.registration.event")
        self.assertEqual(REGISTRATION_EVENT_TABLE, "ops_registration_event")

    def test_writes_the_realtime_row(self):
        result = self.handler.handle(make_event())

        rows = self.store.tables[REGISTRATION_EVENT_TABLE]
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertEqual(set(row), ROW_COLUMNS)
        self.assertEqual(row["_event_id"], "evt-1")
        self.assertEqual(row["event_type"], "APPOINTMENT_CREATED")
        self.assertEqual(row["registration_no"], "R20261104001")
        self.assertEqual(row["hospital_id"], 7)
        self.assertEqual(row["visit_date"], date(2026, 11, 4))
        self.assertEqual(row["period"], 1)
        self.assertEqual(row["occurred_at"], datetime(2026, 11, 4, 10, 0))
        self.assertIsInstance(row["_ingested_at"], datetime)
        self.assertIsNone(row["_ingested_at"].tzinfo)

        self.assertEqual(result.table, REGISTRATION_EVENT_TABLE)
        self.assertEqual(result.event_id, "evt-1")
        self.assertEqual(result.event_type, "APPOINTMENT_CREATED")
        self.assertEqual(result.row, row)

    def test_parses_every_payload_field(self):
        parsed = self.handler.parse(make_event().value)

        self.assertEqual(parsed.event_id, "evt-1")
        self.assertEqual(parsed.event_type, "APPOINTMENT_CREATED")
        self.assertEqual(parsed.occurred_at, datetime(2026, 11, 4, 10, 0))
        self.assertEqual(parsed.registration_no, "R20261104001")
        self.assertEqual(parsed.patient_id, 42)
        self.assertEqual(parsed.hospital_id, 7)
        self.assertEqual(parsed.doctor_name, "李医生")
        self.assertEqual(parsed.department_name, "内科")
        self.assertEqual(parsed.visit_date, date(2026, 11, 4))
        self.assertEqual(parsed.period, 1)
        self.assertEqual(parsed.reason, "复诊")

    def test_row_does_not_carry_personal_fields(self):
        self.handler.handle(make_event())

        row = self.store.tables[REGISTRATION_EVENT_TABLE][0]
        for column in ("patient_id", "doctor_name", "department_name", "reason"):
            self.assertNotIn(column, row)

    def test_optional_payload_fields_tolerated(self):
        body = payload(
            visitDate=None,
            period=None,
            doctorName=None,
            departmentName=None,
            reason=None,
        )
        self.handler.handle(make_event(json.dumps(body, ensure_ascii=False)))

        row = self.store.tables[REGISTRATION_EVENT_TABLE][0]
        self.assertIsNone(row["visit_date"])
        self.assertIsNone(row["period"])
        self.assertEqual(row["hospital_id"], 7)

    def test_inner_payload_object_is_accepted(self):
        self.handler.handle(make_event(payload()))

        row = self.store.tables[REGISTRATION_EVENT_TABLE][0]
        self.assertEqual(row["hospital_id"], 7)

    def test_missing_hospital_id_is_rejected(self):
        body = payload()
        del body["hospitalId"]

        with self.assertRaises(ValueError) as context:
            self.handler.handle(make_event(json.dumps(body)))

        self.assertIn("hospitalId", str(context.exception))
        self.assertEqual(self.store.count(REGISTRATION_EVENT_TABLE), 0)

    def test_missing_registration_no_is_rejected(self):
        body = payload()
        del body["registrationNo"]

        with self.assertRaises(ValueError) as context:
            self.handler.handle(make_event(json.dumps(body)))

        self.assertIn("registrationNo", str(context.exception))
        self.assertEqual(self.store.count(REGISTRATION_EVENT_TABLE), 0)

    def test_invalid_inner_json_is_rejected(self):
        with self.assertRaises(ValueError):
            self.handler.handle(make_event("{not-json"))

    def test_invalid_outer_json_is_rejected(self):
        event = ConsumedEvent(topic=REGISTRATION_EVENT_TOPIC, key=None, value=b"{not-json")

        with self.assertRaises(ValueError):
            self.handler.handle(event)

    def test_missing_envelope_fields_are_rejected(self):
        body = json.loads(make_event().value)
        del body["occurredAt"]

        event = ConsumedEvent(
            topic=REGISTRATION_EVENT_TOPIC,
            key="evt-1",
            value=json.dumps(body).encode("utf-8"),
        )

        with self.assertRaises(ValueError) as context:
            self.handler.handle(event)

        self.assertIn("occurredAt", str(context.exception))

    def test_occurred_at_is_shanghai_wall_clock(self):
        parsed = self.handler.parse(make_event().value)

        expected = datetime.fromtimestamp(OCCURRED_AT.timestamp(), SHANGHAI).replace(tzinfo=None)
        self.assertEqual(parsed.occurred_at, expected)
        self.assertIsNone(parsed.occurred_at.tzinfo)


if __name__ == "__main__":
    unittest.main()
