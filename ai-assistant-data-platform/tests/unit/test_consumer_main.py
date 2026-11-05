# tests/unit/test_consumer_main.py
import json
import signal
import unittest
from datetime import datetime, timedelta, timezone

from etl import consumer_main
from etl.adapters.inmemory_event_bus import InMemoryEventBus
from etl.adapters.inmemory_olap_store import InMemoryOlapStore
from etl.consumer import BookkeepingWriteError, ConsumeResult
from etl.envelope import Envelope
from etl.ports.event_bus import ConsumedEvent
from etl.realtime import REGISTRATION_EVENT_TOPIC, RegistrationEventHandler
from etl.source_registry import SOURCE_SCHEMAS

SHANGHAI = timezone(timedelta(hours=8))


def ods_event(table: str, row: dict, event_id: str = "ods-1") -> bytes:
    return Envelope(
        event_id=event_id,
        event_type="ODS_ROW",
        occurred_at=int(datetime(2026, 11, 4, 12, 0, 0, tzinfo=SHANGHAI).timestamp() * 1000),
        payload={"source_db": "registration_db", "source_table": table, "row": row},
    ).to_bytes()


def registration_event(event_id: str = "evt-1") -> bytes:
    body = {
        "eventId": event_id,
        "eventType": "APPOINTMENT_CREATED",
        "occurredAt": int(datetime(2026, 11, 4, 10, 0, 0, tzinfo=SHANGHAI).timestamp() * 1000),
        "payload": json.dumps(
            {
                "registrationNo": "R20261104001",
                "patientId": 42,
                "hospitalId": 7,
                "doctorName": "李医生",
                "departmentName": "内科",
                "visitDate": "2026-11-04",
                "period": 1,
                "reason": None,
            },
            ensure_ascii=False,
        ),
    }
    return json.dumps(body, ensure_ascii=False).encode("utf-8")


def valid_registration(**overrides) -> dict:
    row = {
        "id": 1,
        "registration_no": "R001",
        "hospital_id": 1,
        "department_id": 2,
        "doctor_user_id": 3,
        "schedule_date": "2026-11-04",
        "period": 1,
        "patient_name": "张三",
        "patient_phone": "13800138000",
        "pay_amount": 10,
        "status": 3,
    }
    row.update(overrides)
    return row


class ScriptedPipeline:
    """Pipeline double that raises/returns the scripted outcomes in order."""

    def __init__(self, outcomes):
        self.outcomes = list(outcomes)
        self.calls = 0
        self.last_events = []

    def run_once(self):
        self.calls += 1
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome


class NoRewindConsumer:
    """Consumer double without the optional ``rewind`` capability."""

    def __init__(self):
        self.closed = False

    def close(self):
        self.closed = True


class SubscribeTopicsTest(unittest.TestCase):
    def test_topics_cover_every_source_table_and_realtime(self):
        topics = consumer_main.subscribe_topics("dp.ods")

        expected = {
            f"dp.ods.{schema}.{table}"
            for table, schema in SOURCE_SCHEMAS.items()
        }
        self.assertEqual(len(SOURCE_SCHEMAS), 7)
        self.assertEqual(len(topics), 8)
        self.assertEqual(set(topics), expected | {REGISTRATION_EVENT_TOPIC})

    def test_topics_follow_the_configured_prefix(self):
        topics = consumer_main.subscribe_topics("dp.stage")

        self.assertIn("dp.stage.hospital_db.hospital", topics)
        self.assertNotIn("dp.ods.hospital_db.hospital", topics)


class BuildPipelineTest(unittest.TestCase):
    def test_wires_dlt_publisher_and_realtime_handler(self):
        bus = InMemoryEventBus()
        store = InMemoryOlapStore()

        pipeline = consumer_main.build_pipeline(bus, store)

        self.assertIs(pipeline.dlt_publisher, bus)
        self.assertIsInstance(pipeline.realtime_handler, RegistrationEventHandler)
        self.assertIs(pipeline.realtime_handler.olap, store)

    def test_handles_ods_and_realtime_in_one_poll(self):
        bus = InMemoryEventBus()
        store = InMemoryOlapStore()
        ods_payload = ods_event("registration", valid_registration())
        bus.publish("dp.ods.registration_db.registration", "1", ods_payload)
        bus.publish(REGISTRATION_EVENT_TOPIC, "evt-1", registration_event())
        pipeline = consumer_main.build_pipeline(bus, store)

        result = pipeline.run_once()

        self.assertEqual(result.inserted, 1)
        self.assertEqual(result.realtime, 1)
        self.assertEqual(store.count("ods_registration"), 1)
        self.assertEqual(store.count("ops_registration_event"), 1)
        self.assertTrue(bus.committed)

    def test_failed_ods_event_is_dead_lettered_to_the_consumer(self):
        bus = InMemoryEventBus()
        store = InMemoryOlapStore()
        bus.publish("dp.ods.registration_db.registration", "k-1", b"{not-json")
        pipeline = consumer_main.build_pipeline(bus, store)

        result = pipeline.run_once()

        self.assertEqual(result.failed, 1)
        self.assertEqual([event.topic for event in bus.pending], ["dp.ods.DLT"])
        self.assertEqual([event.key for event in bus.pending], ["k-1"])
        self.assertTrue(bus.committed)


class ServeTest(unittest.TestCase):
    def test_backs_off_after_bookkeeping_error_without_committing(self):
        bus = InMemoryEventBus()
        sleeps = []
        pipeline = ScriptedPipeline([BookkeepingWriteError("audit down"), KeyboardInterrupt()])

        consumer_main.serve(pipeline, bus, sleep=sleeps.append)

        self.assertEqual(pipeline.calls, 2)
        self.assertEqual(sleeps, [consumer_main.BACKOFF_SECONDS])
        self.assertFalse(bus.committed)
        self.assertTrue(bus.closed)

    def test_sleeps_when_the_poll_is_empty(self):
        bus = InMemoryEventBus()
        sleeps = []
        pipeline = ScriptedPipeline([
            ConsumeResult(consumed=0, inserted=0, quarantined=0, failed=0),
            ConsumeResult(consumed=1, inserted=1, quarantined=0, failed=0),
            KeyboardInterrupt(),
        ])

        consumer_main.serve(pipeline, bus, sleep=sleeps.append)

        self.assertEqual(sleeps, [consumer_main.IDLE_SLEEP_SECONDS])
        self.assertTrue(bus.closed)

    def test_rewinds_the_failed_batch_before_the_next_poll(self):
        bus = InMemoryEventBus()
        sleeps = []
        failed_event = ConsumedEvent(topic="dp.ods", key="1", value=b"payload")
        pipeline = ScriptedPipeline([BookkeepingWriteError("audit down"), KeyboardInterrupt()])
        pipeline.last_events = [failed_event]

        consumer_main.serve(pipeline, bus, sleep=sleeps.append)

        self.assertEqual(bus.pending, [failed_event])
        self.assertEqual(sleeps, [consumer_main.BACKOFF_SECONDS])
        self.assertFalse(bus.committed)
        self.assertTrue(bus.closed)

    def test_bookkeeping_backoff_survives_a_consumer_without_rewind(self):
        consumer = NoRewindConsumer()
        sleeps = []
        pipeline = ScriptedPipeline([BookkeepingWriteError("audit down"), KeyboardInterrupt()])
        pipeline.last_events = [ConsumedEvent(topic="dp.ods", key="1", value=b"payload")]

        consumer_main.serve(pipeline, consumer, sleep=sleeps.append)

        self.assertEqual(pipeline.calls, 2)
        self.assertEqual(sleeps, [consumer_main.BACKOFF_SECONDS])
        self.assertTrue(consumer.closed)

    def test_closes_the_consumer_on_unexpected_error(self):
        bus = InMemoryEventBus()
        pipeline = ScriptedPipeline([RuntimeError("broker unavailable")])

        with self.assertRaises(RuntimeError):
            consumer_main.serve(pipeline, bus, sleep=lambda _: None)

        self.assertTrue(bus.closed)


class SigtermTest(unittest.TestCase):
    def test_sigterm_raises_keyboard_interrupt_for_the_graceful_path(self):
        with self.assertRaises(KeyboardInterrupt):
            consumer_main.handle_sigterm(signal.SIGTERM, None)


if __name__ == "__main__":
    unittest.main()
