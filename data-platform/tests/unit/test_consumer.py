# tests/unit/test_consumer.py
import json
import unittest
from datetime import datetime, timedelta, timezone

from etl.adapters.inmemory_event_bus import InMemoryEventBus
from etl.adapters.inmemory_olap_store import InMemoryOlapStore
from etl.consumer import BookkeepingWriteError, ConsumeResult, ConsumerPipeline, now_shanghai
from etl.envelope import Envelope
from etl.realtime import REGISTRATION_EVENT_TOPIC


def make_event(table: str, row: dict, event_id: str = "e-1") -> bytes:
    return Envelope(
        event_id=event_id,
        event_type="ODS_ROW",
        occurred_at=int(datetime(2026, 11, 4, 12, 0, 0).timestamp() * 1000),
        payload={"source_db": "registration_db", "source_table": table, "row": row},
    ).to_bytes()


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


class FakePublisher:
    def __init__(self):
        self.messages = []
        self.flushes = 0

    def publish(self, topic, key, value):
        self.messages.append((topic, key, value))

    def flush(self):
        self.flushes += 1


class FlakyOlapStore(InMemoryOlapStore):
    """Fails the first ``failures`` ODS writes, then behaves normally."""

    def __init__(self, failures: int):
        super().__init__()
        self.remaining = failures

    def insert_json(self, table, rows):
        if self.remaining > 0 and table.startswith("ods_"):
            self.remaining -= 1
            raise RuntimeError("transient write failure")
        super().insert_json(table, rows)


class QuarantineFailStore(InMemoryOlapStore):
    """Always fails quarantine writes and counts the attempts."""

    def __init__(self):
        super().__init__()
        self.quarantine_attempts = 0

    def insert_json(self, table, rows):
        if table == "ops_quarantine":
            self.quarantine_attempts += 1
            raise RuntimeError("quarantine unavailable")
        super().insert_json(table, rows)


class AuditFailStore(InMemoryOlapStore):
    """Always fails audit writes."""

    def insert_json(self, table, rows):
        if table == "ops_ingest_audit":
            raise RuntimeError("audit unavailable")
        super().insert_json(table, rows)


class ConsumerTest(unittest.TestCase):
    def test_inserts_valid_rows_and_masks_pii(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        bus.publish("dp.ods.registration_db.registration", "1", make_event("registration", {
            "id": 1, "hospital_id": 1, "status": 3, "schedule_date": "2026-11-04", "period": 1,
            "patient_name": "张三", "patient_phone": "13800138000", "pay_amount": 10,
        }))
        result = ConsumerPipeline(bus, olap).run_once()
        self.assertEqual(result.inserted, 1)
        rows = olap.tables["ods_registration"]
        self.assertEqual(rows[0]["patient_name_masked"], "张*")
        self.assertTrue(bus.committed)

    def test_duplicate_event_is_idempotent(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        payload = make_event(
            "registration",
            {
                "id": 1,
                "hospital_id": 1,
                "status": 3,
                "schedule_date": "2026-11-04",
                "period": 1,
            },
        )
        bus.publish("dp.ods", "1", payload)
        bus.publish("dp.ods", "1", payload)
        result = ConsumerPipeline(bus, olap).run_once()
        self.assertEqual(result.inserted, 1)
        self.assertEqual(olap.count("ods_registration"), 1)

    def test_bad_row_quarantined(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        bus.publish(
            "dp.ods",
            "1",
            make_event("registration", {"id": 1, "hospital_id": 1, "status": 99}),
        )
        result = ConsumerPipeline(bus, olap).run_once()
        self.assertEqual(result.quarantined, 1)
        self.assertEqual(olap.count("ops_quarantine"), 1)

    def test_inserted_rows_carry_ingestion_metadata(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        bus.publish("dp.ods", "1", make_event("registration", valid_registration()))

        ConsumerPipeline(bus, olap).run_once()

        row = olap.tables["ods_registration"][0]
        self.assertEqual(row["_event_id"], "e-1")
        self.assertEqual(row["_source"], "registration_db")
        self.assertIsInstance(row["_ingested_at"], datetime)
        self.assertIsNone(row["_ingested_at"].tzinfo)

    def test_quarantine_row_has_json_sample_and_reasons(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        bus.publish(
            "dp.ods",
            "1",
            make_event("registration", {"id": 1, "hospital_id": 1, "status": 99}),
        )

        ConsumerPipeline(bus, olap).run_once()

        quarantine = olap.tables["ops_quarantine"][0]
        self.assertIsInstance(quarantine["sample"], str)
        self.assertEqual(json.loads(quarantine["sample"])["status"], 99)
        self.assertIn("status", quarantine["errors"])
        self.assertEqual(quarantine["source_table"], "registration")
        self.assertEqual(quarantine["_event_id"], "e-1")
        self.assertTrue(quarantine["batch_id"])
        self.assertIsNone(quarantine["_ingested_at"].tzinfo)

    def test_quarantine_sample_strips_pii(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        row = valid_registration(
            status=99,
            patient_name="张三",
            patient_phone="13800138000",
            patient_id_card="110101199001011234",
        )
        bus.publish("dp.ods", "1", make_event("registration", row))

        ConsumerPipeline(bus, olap).run_once()

        sample = json.loads(olap.tables["ops_quarantine"][0]["sample"])
        self.assertEqual(sample["status"], 99)
        self.assertEqual(sample["id"], 1)
        for key in ("patient_name", "patient_phone", "patient_id_card"):
            self.assertNotIn(key, sample)
        self.assertNotIn("张三", olap.tables["ops_quarantine"][0]["sample"])
        self.assertNotIn("13800138000", olap.tables["ops_quarantine"][0]["sample"])

    def test_quarantine_and_audit_share_batch_id(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        bus.publish(
            "dp.ods",
            "1",
            make_event(
                "registration",
                {"id": 1, "hospital_id": 1, "status": 99},
                event_id="e-1",
            ),
        )
        bus.publish("dp.ods", "2", make_event("hospital", {"id": 5, "name": "H"}, event_id="e-2"))

        ConsumerPipeline(bus, olap).run_once()

        quarantine_batch = olap.tables["ops_quarantine"][0]["batch_id"]
        audit_batch = olap.tables["ops_ingest_audit"][0]["batch_id"]
        self.assertEqual(quarantine_batch, audit_batch)

    def test_audit_row_per_table_per_batch(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        bus.publish("dp.ods", "1", make_event("registration", valid_registration(), event_id="e-1"))
        bus.publish("dp.ods", "2", make_event("hospital", {"id": 5, "name": "H"}, event_id="e-2"))

        ConsumerPipeline(bus, olap).run_once()

        audit = olap.tables["ops_ingest_audit"]
        self.assertEqual(len(audit), 2)
        self.assertEqual(sorted(row["source_table"] for row in audit), ["hospital", "registration"])
        self.assertEqual({row["batch_id"] for row in audit}, {audit[0]["batch_id"]})
        self.assertTrue(all(row["rows_ingested"] == 1 for row in audit))
        self.assertTrue(all(isinstance(row["_ingested_at"], datetime) for row in audit))

    def test_duplicate_rows_are_not_counted_in_audit(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        payload = make_event("registration", valid_registration())
        bus.publish("dp.ods", "1", payload)
        bus.publish("dp.ods", "1", payload)

        result = ConsumerPipeline(bus, olap).run_once()

        self.assertEqual(result.consumed, 2)
        self.assertEqual(olap.tables["ops_ingest_audit"][0]["rows_ingested"], 1)

    def test_undecodable_event_goes_to_dlt(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        dlt = FakePublisher()
        bus.publish("dp.ods", "k-1", b"{not-json")

        result = ConsumerPipeline(bus, olap, dlt_publisher=dlt).run_once()

        self.assertEqual(result.failed, 1)
        self.assertEqual(result.inserted, 0)
        self.assertEqual(dlt.messages, [("dp.ods.DLT", "k-1", b"{not-json")])
        self.assertEqual(olap.count("ops_ingest_audit"), 0)
        self.assertTrue(bus.committed)

    def test_transient_insert_failure_is_retried(self):
        bus = InMemoryEventBus()
        olap = FlakyOlapStore(failures=2)
        bus.publish("dp.ods", "1", make_event("registration", valid_registration()))

        result = ConsumerPipeline(bus, olap, max_retries=3).run_once()

        self.assertEqual(result.inserted, 1)
        self.assertEqual(result.failed, 0)
        self.assertEqual(olap.count("ods_registration"), 1)

    def test_exhausted_retries_count_as_failed(self):
        bus = InMemoryEventBus()
        olap = FlakyOlapStore(failures=10)
        dlt = FakePublisher()
        bus.publish("dp.ods", "1", make_event("registration", valid_registration()))

        result = ConsumerPipeline(bus, olap, dlt_publisher=dlt, max_retries=2).run_once()

        self.assertEqual(result.failed, 1)
        self.assertEqual(result.inserted, 0)
        self.assertEqual(olap.count("ods_registration"), 0)
        self.assertEqual(len(dlt.messages), 1)
        self.assertTrue(bus.committed)

    def test_quarantine_write_failure_is_retried_and_blocks_commit(self):
        bus = InMemoryEventBus()
        olap = QuarantineFailStore()
        bus.publish(
            "dp.ods",
            "1",
            make_event("registration", {"id": 1, "hospital_id": 1, "status": 99}),
        )

        with self.assertRaises(BookkeepingWriteError):
            ConsumerPipeline(bus, olap, max_retries=2).run_once()

        self.assertFalse(bus.committed)
        self.assertEqual(olap.quarantine_attempts, 2)
        self.assertEqual(olap.count("ops_quarantine"), 0)

    def test_audit_write_failure_blocks_commit(self):
        bus = InMemoryEventBus()
        olap = AuditFailStore()
        bus.publish("dp.ods", "1", make_event("registration", valid_registration()))

        with self.assertRaises(BookkeepingWriteError):
            ConsumerPipeline(bus, olap).run_once()

        self.assertFalse(bus.committed)
        self.assertEqual(olap.count("ods_registration"), 1)

    def test_now_shanghai_is_naive_utc_plus_eight(self):
        value = now_shanghai()
        expected = datetime.now(timezone(timedelta(hours=8))).replace(tzinfo=None)
        self.assertIsNone(value.tzinfo)
        self.assertLess(abs((value - expected).total_seconds()), 5)

    def test_poll_respects_max_records(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        for index in range(3):
            bus.publish(
                "dp.ods",
                str(index),
                make_event("registration", valid_registration(id=index + 1), event_id=f"e-{index}"),
            )

        first = ConsumerPipeline(bus, olap).run_once(max_records=2)

        self.assertEqual(first.consumed, 2)
        self.assertEqual(olap.count("ods_registration"), 2)
        self.assertEqual(len(bus.pending), 1)


class FakeRealtimeHandler:
    """Stand-in for RegistrationEventHandler that records routed events."""

    def __init__(self, failures: int = 0):
        self.failures = failures
        self.events = []

    def handle(self, event):
        self.events.append(event)
        if self.failures > 0:
            self.failures -= 1
            raise RuntimeError("realtime sink unavailable")
        return {"event_id": event.key or "realtime-1"}


class RealtimeRoutingTest(unittest.TestCase):
    def test_registration_topic_routes_to_the_handler(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        handler = FakeRealtimeHandler()
        bus.publish(REGISTRATION_EVENT_TOPIC, "evt-1", b"opaque-mq-envelope")

        result = ConsumerPipeline(bus, olap, realtime_handler=handler).run_once()

        self.assertEqual(result.realtime, 1)
        self.assertEqual(result.inserted, 0)
        self.assertEqual(result.failed, 0)
        self.assertEqual(len(handler.events), 1)
        self.assertEqual(handler.events[0].topic, REGISTRATION_EVENT_TOPIC)
        self.assertEqual(olap.tables, {})
        self.assertEqual(olap.count("ops_ingest_audit"), 0)
        self.assertTrue(bus.committed)

    def test_non_ods_event_type_routes_to_the_handler(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        handler = FakeRealtimeHandler()
        body = {
            "eventId": "evt-2",
            "eventType": "APPOINTMENT_CREATED",
            "occurredAt": 1762336800000,
            "payload": "{}",
        }
        bus.publish("dp.ods", "evt-2", json.dumps(body).encode("utf-8"))

        result = ConsumerPipeline(bus, olap, realtime_handler=handler).run_once()

        self.assertEqual(result.realtime, 1)
        self.assertEqual(len(handler.events), 1)
        self.assertEqual(olap.tables, {})

    def test_ods_rows_still_use_the_cleaner(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        handler = FakeRealtimeHandler()
        payload = make_event("registration", valid_registration())
        bus.publish("dp.ods.registration_db.registration", "1", payload)

        result = ConsumerPipeline(bus, olap, realtime_handler=handler).run_once()

        self.assertEqual(result.inserted, 1)
        self.assertEqual(result.realtime, 0)
        self.assertEqual(handler.events, [])
        self.assertEqual(olap.count("ods_registration"), 1)

    def test_realtime_transient_failure_is_retried(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        handler = FakeRealtimeHandler(failures=1)
        bus.publish(REGISTRATION_EVENT_TOPIC, "evt-1", b"value")

        result = ConsumerPipeline(bus, olap, realtime_handler=handler, max_retries=3).run_once()

        self.assertEqual(result.realtime, 1)
        self.assertEqual(result.failed, 0)
        self.assertEqual(len(handler.events), 2)
        self.assertTrue(bus.committed)

    def test_realtime_failure_is_retried_then_dead_lettered(self):
        bus = InMemoryEventBus()
        olap = InMemoryOlapStore()
        handler = FakeRealtimeHandler(failures=10)
        dlt = FakePublisher()
        bus.publish(REGISTRATION_EVENT_TOPIC, "evt-9", b"bad-envelope")

        result = ConsumerPipeline(
            bus, olap, dlt_publisher=dlt, realtime_handler=handler, max_retries=2
        ).run_once()

        self.assertEqual(result.failed, 1)
        self.assertEqual(result.realtime, 0)
        self.assertEqual(len(handler.events), 2)
        self.assertEqual(dlt.messages, [("dp.ods.DLT", "evt-9", b"bad-envelope")])
        self.assertTrue(bus.committed)

    def test_consume_result_realtime_defaults_to_zero(self):
        result = ConsumeResult(consumed=1, inserted=1, quarantined=0, failed=0)

        self.assertEqual(result.realtime, 0)


class InMemoryAdapterTest(unittest.TestCase):
    def test_event_bus_implements_publisher_and_consumer_contract(self):
        bus = InMemoryEventBus()
        bus.subscribe(["dp.ods.registration_db.registration"])
        bus.publish("dp.ods.registration_db.registration", "1", b"value")
        bus.flush()
        events = bus.poll(timeout_seconds=0, max_records=10)
        bus.commit()
        bus.close()

        self.assertEqual(bus.subscribed_topics, ["dp.ods.registration_db.registration"])
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0].topic, "dp.ods.registration_db.registration")
        self.assertEqual(events[0].key, "1")
        self.assertEqual(events[0].value, b"value")
        self.assertTrue(bus.committed)
        self.assertTrue(bus.closed)

    def test_olap_store_dedupes_by_event_id_and_records_sql(self):
        store = InMemoryOlapStore()
        store.insert_json("ods_registration", [{"_event_id": "e-1", "id": 1}])
        store.insert_json("ods_registration", [{"_event_id": "e-1", "id": 2}])
        store.insert_json("ods_registration", [{"_event_id": "e-2", "id": 3}])
        store.execute("SELECT 1")

        self.assertEqual(store.count("ods_registration"), 2)
        self.assertEqual([row["id"] for row in store.tables["ods_registration"]], [1, 3])
        self.assertEqual(store.executed, ["SELECT 1"])
        self.assertEqual(store.query("SELECT 1"), [])


if __name__ == "__main__":
    unittest.main()
