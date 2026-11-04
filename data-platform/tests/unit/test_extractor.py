# tests/unit/test_extractor.py
import unittest
from datetime import datetime
from decimal import Decimal

from etl.envelope import Envelope
from etl.extractor import ExtractResult, Extractor, SyncTaskSpec


class FakeSource:
    def __init__(self, batches, max_value=None):
        self.batches = list(batches)
        self.calls = 0
        self.full_calls = []
        self.incremental_calls = []
        self.max_value = max_value if max_value is not None else datetime(2026, 11, 4, 12, 0, 0)

    def fetch_full_batch(self, schema, table, columns, primary_key, after_pk, limit):
        self.calls += 1
        self.full_calls.append(
            {
                "schema": schema,
                "table": table,
                "columns": columns,
                "primary_key": primary_key,
                "after_pk": after_pk,
                "limit": limit,
            }
        )
        return self.batches.pop(0) if self.batches else []

    def fetch_incremental_batch(
        self,
        schema,
        table,
        columns,
        primary_key,
        watermark_column,
        after_watermark,
        after_pk,
        limit,
    ):
        self.calls += 1
        self.incremental_calls.append(
            {
                "watermark_column": watermark_column,
                "after_watermark": after_watermark,
                "after_pk": after_pk,
                "limit": limit,
            }
        )
        return self.batches.pop(0) if self.batches else []

    def max_watermark(self, schema, table, watermark_column):
        return self.max_value


class FakePublisher:
    def __init__(self):
        self.messages = []
        self.flushes = 0

    def publish(self, topic, key, value):
        self.messages.append((topic, key, value))

    def flush(self):
        self.flushes += 1


class FakeRunStore:
    def __init__(self):
        self.started = []
        self.checkpoints = []
        self.finished = []

    def start_run(self, task_id, run_type):
        self.started.append((task_id, run_type))
        return 1

    def checkpoint(self, run_id, rows_read, rows_written, cursor):
        self.checkpoints.append((rows_read, rows_written, cursor))

    def finish_run(self, run_id, status, rows_read, rows_written, error=None):
        self.finished.append((status, rows_read, rows_written, error))


class ExtractorTest(unittest.TestCase):
    def test_full_load_publishes_rows_and_finishes(self):
        task = SyncTaskSpec(
            id=1,
            source_schema="registration_db",
            source_table="registration",
            target_table="ods_registration",
            primary_key="id",
            watermark_column="update_time",
            batch_size=2,
        )
        source = FakeSource(
            [
                [
                    {"id": 1, "update_time": "2026-11-04 10:00:00"},
                    {"id": 2, "update_time": "2026-11-04 10:00:01"},
                ]
            ]
        )
        publisher = FakePublisher()
        run_store = FakeRunStore()

        result = Extractor(source, publisher, "dp.ods").run(task, "FULL", run_store)

        self.assertIsInstance(result, ExtractResult)
        self.assertEqual(result.rows_read, 2)
        self.assertEqual(len(publisher.messages), 2)
        self.assertEqual(run_store.finished[0][0], "SUCCESS")
        self.assertEqual(Envelope.from_bytes(publisher.messages[0][2]).event_type, "ODS_ROW")

    def test_incremental_uses_watermark_and_checkpoint(self):
        task = SyncTaskSpec(
            id=2,
            source_schema="chat_db",
            source_table="chat_session",
            target_table="ods_chat_session",
            primary_key="id",
            watermark_column="updated_at",
            batch_size=100,
            full_load_done=True,
            last_watermark=datetime(2026, 11, 4, 10, 0, 0),
        )
        source = FakeSource([[{"id": 5, "updated_at": datetime(2026, 11, 4, 10, 1, 0)}]])
        publisher = FakePublisher()
        run_store = FakeRunStore()

        result = Extractor(source, publisher, "dp.ods").run(task, "INCREMENTAL", run_store)

        self.assertEqual(result.rows_read, 1)
        self.assertEqual(run_store.started[0][1], "INCREMENTAL")
        self.assertTrue(run_store.checkpoints)

    def test_watermark_type_is_carried_by_result(self):
        task = SyncTaskSpec(
            id=3,
            source_schema="chat_db",
            source_table="chat_message",
            target_table="ods_chat_message",
            primary_key="id",
            watermark_column="updated_at",
            full_load_done=True,
            last_watermark=datetime(2026, 11, 4, 10, 0, 0),
            overlap_seconds=60,
        )
        source = FakeSource([[{"id": 9, "updated_at": datetime(2026, 11, 4, 10, 1, 0)}]])

        result = Extractor(source, FakePublisher(), "dp.ods").run(
            task, "INCREMENTAL", FakeRunStore()
        )

        # overlap window still applies to datetime watermarks
        self.assertEqual(
            source.incremental_calls[0]["after_watermark"], datetime(2026, 11, 4, 9, 59, 0)
        )
        self.assertEqual(result.last_watermark, datetime(2026, 11, 4, 10, 1, 0))
        self.assertIsNone(result.last_watermark_num)

    def test_incremental_numeric_watermark_has_no_time_overlap(self):
        task = SyncTaskSpec(
            id=4,
            source_schema="chat_db",
            source_table="chat_message",
            target_table="ods_chat_message",
            primary_key="id",
            watermark_column="seq",
            watermark_type="numeric",
            batch_size=100,
            full_load_done=True,
            last_watermark_num=Decimal("100"),
        )
        source = FakeSource([[{"id": 7, "seq": 120}]])
        publisher = FakePublisher()
        run_store = FakeRunStore()

        result = Extractor(source, publisher, "dp.ods").run(task, "INCREMENTAL", run_store)

        self.assertEqual(result.rows_read, 1)
        self.assertEqual(source.incremental_calls[0]["after_watermark"], Decimal("100"))
        self.assertEqual(result.last_watermark_num, Decimal("120"))
        self.assertIsNone(result.last_watermark)
        self.assertEqual(run_store.checkpoints[0][2]["after_watermark"], "120")

    def test_full_load_captures_numeric_watermark(self):
        task = SyncTaskSpec(
            id=5,
            source_schema="chat_db",
            source_table="chat_message",
            target_table="ods_chat_message",
            primary_key="id",
            watermark_column="seq",
            watermark_type="numeric",
        )
        source = FakeSource(
            [[{"id": 1, "seq": 10}, {"id": 2, "seq": 20}]],
            max_value=Decimal("20"),
        )

        result = Extractor(source, FakePublisher(), "dp.ods").run(task, "FULL", FakeRunStore())

        self.assertEqual(result.rows_read, 2)
        self.assertEqual(result.last_watermark_num, Decimal("20"))
        self.assertIsNone(result.last_watermark)

    def test_incremental_without_watermark_configuration_fails_the_run(self):
        task = SyncTaskSpec(
            id=6,
            source_schema="chat_db",
            source_table="chat_session",
            target_table="ods_chat_session",
            primary_key="id",
            watermark_column=None,
            full_load_done=True,
        )
        run_store = FakeRunStore()

        with self.assertRaises(ValueError):
            Extractor(FakeSource([]), FakePublisher(), "dp.ods").run(task, "INCREMENTAL", run_store)

        self.assertEqual(run_store.finished[0][0], "FAILED")

    def test_numeric_incremental_requires_last_numeric_watermark(self):
        task = SyncTaskSpec(
            id=7,
            source_schema="chat_db",
            source_table="chat_message",
            target_table="ods_chat_message",
            primary_key="id",
            watermark_column="seq",
            watermark_type="numeric",
            full_load_done=True,
        )
        run_store = FakeRunStore()

        with self.assertRaises(ValueError):
            Extractor(FakeSource([]), FakePublisher(), "dp.ods").run(task, "INCREMENTAL", run_store)

        self.assertEqual(run_store.finished[0][0], "FAILED")

    def test_partition_key_prefers_hospital_id(self):
        task = SyncTaskSpec(
            id=9,
            source_schema="registration_db",
            source_table="registration",
            target_table="ods_registration",
            primary_key="id",
            watermark_column=None,
        )
        source = FakeSource([[{"id": 1, "hospital_id": 7}, {"id": 2, "hospital_id": 8}, {"id": 3}]])
        publisher = FakePublisher()

        Extractor(source, publisher, "dp.ods").run(task, "FULL", FakeRunStore())

        self.assertEqual([message[1] for message in publisher.messages], ["7", "8", "3"])

    def test_rejects_unknown_mode(self):
        task = SyncTaskSpec(
            id=8,
            source_schema="chat_db",
            source_table="chat_session",
            target_table="ods_chat_session",
            primary_key="id",
            watermark_column=None,
        )
        with self.assertRaises(ValueError):
            Extractor(FakeSource([]), FakePublisher(), "dp.ods").run(task, "DELTA", FakeRunStore())

    def test_full_load_event_contract_and_keyset_continuation(self):
        task = SyncTaskSpec(
            id=10,
            source_schema="registration_db",
            source_table="registration",
            target_table="ods_registration",
            primary_key="id",
            watermark_column=None,
            batch_size=1,
        )
        source = FakeSource([[{"id": 1}], [{"id": 2}]])
        publisher = FakePublisher()

        result = Extractor(source, publisher, "dp.ods").run(task, "FULL", FakeRunStore())

        self.assertEqual(result.rows_read, 2)
        # topic format: dp.ods.<schema>.<table>
        self.assertEqual(publisher.messages[0][0], "dp.ods.registration_db.registration")
        self.assertEqual(publisher.messages[1][0], "dp.ods.registration_db.registration")
        envelope = Envelope.from_bytes(publisher.messages[0][2])
        self.assertEqual(envelope.payload["source_db"], "registration_db")
        self.assertEqual(envelope.payload["source_table"], "registration")
        self.assertEqual(envelope.payload["row"], {"id": 1})
        # the publisher is flushed once per published batch
        self.assertEqual(publisher.flushes, 2)
        # the second batch continues after the last PK of the first batch
        self.assertEqual([call["after_pk"] for call in source.full_calls], [None, 1, 2])
        self.assertEqual(source.full_calls[0]["primary_key"], "id")
        self.assertEqual(source.full_calls[0]["limit"], 1)


if __name__ == "__main__":
    unittest.main()
