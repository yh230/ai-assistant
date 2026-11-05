# tests/unit/test_alerting.py
import importlib.util
import operator
import sys
import unittest
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest import mock

from etl.alerting import (
    AlertEvent,
    AlertRule,
    build_alert_event,
    build_webhook_payload,
    default_webhook_sender,
    fingerprint,
    local_now,
    now_shanghai,
    should_fire,
    to_local_naive,
)

HAS_STACK = all(
    importlib.util.find_spec(name) is not None
    for name in ("fastapi", "sqlalchemy", "pydantic", "pydantic_settings")
)

SHANGHAI_OFFSET = timezone(timedelta(hours=8))


def _rule(**overrides):
    values = dict(
        id=1,
        name="lag",
        metric_type="PIPELINE_LAG",
        operator=">",
        threshold=300,
        window_minutes=5,
        consecutive_periods=1,
        severity="P0",
        cooldown_minutes=30,
    )
    values.update(overrides)
    return AlertRule(**values)


def _rule_row(**overrides):
    values = dict(
        id=1,
        name="链路延迟",
        metric_type="PIPELINE_LAG",
        operator=">",
        threshold=300.0,
        window_minutes=5,
        consecutive_periods=1,
        severity="P0",
        channel="webhook",
        cooldown_minutes=30,
        enabled=True,
    )
    values.update(overrides)
    return SimpleNamespace(**values)


def _alert_row(**overrides):
    values = dict(
        id=7,
        rule_id=1,
        fingerprint="abc123",
        severity="P0",
        title="链路延迟",
        content="lag=600",
        status="OPEN",
        created_at=datetime(2026, 11, 4, 12, 0, 0),
        ack_at=None,
        closed_at=None,
    )
    values.update(overrides)
    return SimpleNamespace(**values)


_MISSING = object()


def _criterion_matches(row, criterion):
    """Evaluate a simple SQLAlchemy ``column <op> value`` predicate.

    ``AlertService.latest_record`` builds ``DpAlertRecord.fingerprint ==
    value``; the fake session must apply that predicate rather than merely
    record it, otherwise a record for another hospital would satisfy the
    cooldown check.  Criteria the fake cannot interpret keep the row.
    """
    left = getattr(criterion, "left", None)
    right = getattr(criterion, "right", None)
    key = getattr(left, "key", None)
    compare = getattr(criterion, "operator", None)
    value = getattr(right, "value", _MISSING)
    if key is None or compare is None or value is _MISSING:
        return True
    return bool(compare(getattr(row, key), value))


class FakeQuery:
    def __init__(self, rows):
        # ``source`` stays aliased to the live ``FakeDb.records`` list so a
        # later query still sees records added after an earlier filter ran.
        self.source = rows
        self.rows = rows
        self.filters = []
        self.order_by_args = []
        self.limit_value = None

    def filter(self, *criteria):
        self.filters.append(criteria)
        self.rows = [
            row
            for row in self.source
            if all(
                _criterion_matches(row, criterion)
                for group in self.filters
                for criterion in group
            )
        ]
        return self

    def order_by(self, *criteria):
        self.order_by_args.append(criteria)
        return self

    def limit(self, value):
        self.limit_value = value
        return self

    def all(self):
        return list(self.rows)

    def first(self):
        return self.rows[0] if self.rows else None


class FakeDb:
    """Minimal session double for the alert service and routes."""

    def __init__(self, records=None):
        self.records = list(records or [])
        self.query_obj = FakeQuery(self.records)
        self.get_rows = {}
        self.added = []
        self.deleted = []
        self.commits = 0
        self.refreshes = 0
        self.rolled_back = False
        self._next_id = 500

    def query(self, model):
        return self.query_obj

    def get(self, model, key):
        return self.get_rows.get(key)

    def add(self, obj):
        self.added.append(obj)
        if getattr(obj, "id", None) is None:
            obj.id = self._next_id
            self._next_id += 1
        # Keep the fake "latest record" query aligned with what was written.
        self.records.insert(0, obj)

    def commit(self):
        self.commits += 1

    def refresh(self, obj):
        self.refreshes += 1

    def rollback(self):
        self.rolled_back = True

    def delete(self, obj):
        self.deleted.append(obj)


class FakeQueryFilterTest(unittest.TestCase):
    """The session double must apply the predicates real SQLAlchemy applies."""

    @staticmethod
    def _equals(column, value):
        return SimpleNamespace(
            left=SimpleNamespace(key=column),
            right=SimpleNamespace(value=value),
            operator=operator.eq,
        )

    def test_filter_keeps_only_matching_rows(self):
        matching = SimpleNamespace(fingerprint="a")
        other = SimpleNamespace(fingerprint="b")
        query = FakeQuery([other, matching])

        self.assertEqual(
            query.filter(self._equals("fingerprint", "a")).all(), [matching]
        )
        self.assertEqual(len(query.filters), 1)

    def test_chained_filters_accumulate(self):
        row = SimpleNamespace(fingerprint="a", status="OPEN")
        query = FakeQuery([row])

        query.filter(self._equals("fingerprint", "a"))
        query.filter(self._equals("status", "CLOSED"))

        self.assertEqual(query.all(), [])

    def test_unrecognised_criteria_keep_the_rows(self):
        row = SimpleNamespace(fingerprint="a")

        self.assertEqual(FakeQuery([row]).filter(SimpleNamespace()).all(), [row])


# The four tests from the task brief.  The payload is the DingTalk-compatible
# nested shape mandated by the brief's own implementation, so the title lives
# under ``payload["text"]["content"]`` (the brief's suffix assertion pointed at
# the dict itself, which no implementation of that payload could satisfy).
class AlertingTest(unittest.TestCase):
    def test_fingerprint_is_stable(self):
        self.assertEqual(fingerprint(1, "hospital:1"), fingerprint(1, "hospital:1"))
        self.assertNotEqual(fingerprint(1, "hospital:1"), fingerprint(1, "hospital:2"))

    def test_cooldown_blocks_duplicate(self):
        rule = AlertRule(id=1, name="lag", metric_type="PIPELINE_LAG", operator=">", threshold=300, window_minutes=5, consecutive_periods=1, severity="P0", cooldown_minutes=30)
        now = datetime(2026, 11, 4, 12, 0, 0)
        self.assertTrue(should_fire(rule, 600, 1, None, now))
        self.assertFalse(should_fire(rule, 600, 1, now - timedelta(minutes=10), now))
        self.assertTrue(should_fire(rule, 600, 1, now - timedelta(minutes=31), now))

    def test_consecutive_periods_required(self):
        rule = AlertRule(id=1, name="lag", metric_type="PIPELINE_LAG", operator=">", threshold=300, window_minutes=5, consecutive_periods=3, severity="P0", cooldown_minutes=30)
        now = datetime(2026, 11, 4, 12, 0, 0)
        self.assertFalse(should_fire(rule, 600, 2, None, now))
        self.assertTrue(should_fire(rule, 600, 3, None, now))

    def test_webhook_payload_contains_title(self):
        event = AlertEvent(rule_id=1, fingerprint="abc", severity="P0", title="链路延迟", content="lag=600s")
        payload = build_webhook_payload(event)
        self.assertIn("链路延迟", payload["text"]["content"])


class AlertingFingerprintTest(unittest.TestCase):
    def test_fingerprint_is_sixteen_hex_characters(self):
        value = fingerprint(1, "hospital:1")
        self.assertEqual(len(value), 16)
        self.assertTrue(all(char in "0123456789abcdef" for char in value))

    def test_rule_id_is_part_of_the_identity(self):
        self.assertNotEqual(fingerprint(1, "hospital:1"), fingerprint(2, "hospital:1"))


class AlertingOperatorTest(unittest.TestCase):
    def test_supported_operators(self):
        now = datetime(2026, 11, 4, 12, 0, 0)
        cases = {
            ">": (11, True),
            ">=": (10, True),
            "<": (9, True),
            "<=": (10, True),
            "==": (10, True),
        }
        for op, (value, expected) in cases.items():
            with self.subTest(operator=op):
                rule = _rule(operator=op, threshold=10)
                self.assertEqual(should_fire(rule, value, 1, None, now), expected)

    def test_value_must_breach_the_threshold(self):
        now = datetime(2026, 11, 4, 12, 0, 0)
        self.assertFalse(should_fire(_rule(), 300, 1, None, now))
        self.assertFalse(should_fire(_rule(), 299.9, 1, None, now))

    def test_unknown_operator_is_rejected(self):
        with self.assertRaises(ValueError) as context:
            should_fire(_rule(operator="!="), 600, 1, None, datetime(2026, 11, 4, 12, 0, 0))
        self.assertIn("unknown operator", str(context.exception))

    def test_cooldown_boundary_is_exclusive(self):
        now = datetime(2026, 11, 4, 12, 0, 0)
        self.assertFalse(
            should_fire(_rule(), 600, 1, now - timedelta(minutes=29, seconds=59), now)
        )
        self.assertTrue(
            should_fire(_rule(), 600, 1, now - timedelta(minutes=30), now)
        )

    def test_zero_cooldown_never_blocks(self):
        now = datetime(2026, 11, 4, 12, 0, 0)
        self.assertTrue(should_fire(_rule(cooldown_minutes=0), 600, 1, now, now))

    def test_aware_timestamps_are_normalised(self):
        # 04:00 UTC is 12:00 in Asia/Shanghai, ten minutes into the cooldown.
        last_fired = datetime(2026, 11, 4, 4, 0, tzinfo=timezone.utc)
        now = datetime(2026, 11, 4, 12, 10, 0)
        self.assertFalse(should_fire(_rule(), 600, 1, last_fired, now))


class AlertingEventTest(unittest.TestCase):
    def test_event_carries_title_fingerprint_and_context(self):
        moment = datetime(2026, 11, 4, 12, 0, 0)
        event = build_alert_event(_rule(name="链路延迟"), 600, "hospital:1", moment)
        self.assertEqual(event.rule_id, 1)
        self.assertEqual(event.fingerprint, fingerprint(1, "hospital:1"))
        self.assertEqual(event.severity, "P0")
        self.assertIn("链路延迟", event.title)
        self.assertIn("PIPELINE_LAG", event.content)
        self.assertIn("value=600", event.content)
        self.assertIn("dimension=hospital:1", event.content)
        self.assertIn("at=2026-11-04 12:00:00", event.content)

    def test_event_title_contains_rule_name_and_metric(self):
        event = build_alert_event(
            _rule(name="registration lag"), 1, "global", datetime(2026, 11, 4, 12, 0, 0)
        )
        self.assertEqual(event.title, "registration lag (PIPELINE_LAG)")

    def test_blank_dimension_falls_back_to_global(self):
        event = build_alert_event(_rule(), 600, "   ", datetime(2026, 11, 4, 12, 0, 0))
        self.assertEqual(event.fingerprint, fingerprint(1, "global"))
        self.assertIn("dimension=global", event.content)

    def test_payload_is_dingtalk_compatible_text(self):
        event = AlertEvent(
            rule_id=1, fingerprint="abc", severity="P0", title="链路延迟", content="lag=600s"
        )
        payload = build_webhook_payload(event)
        self.assertEqual(payload["msgtype"], "text")
        content = payload["text"]["content"]
        self.assertIn("[P0] 链路延迟", content)
        self.assertIn("lag=600s", content)
        self.assertIn("fingerprint=abc", content)


class AlertingTimezoneTest(unittest.TestCase):
    def test_naive_values_are_kept_as_local_wall_clock(self):
        value = datetime(2026, 11, 4, 12, 0, 0)
        self.assertEqual(to_local_naive(value), value)

    def test_utc_aware_values_convert_to_shanghai(self):
        self.assertEqual(
            to_local_naive(datetime(2026, 11, 4, 4, 0, tzinfo=timezone.utc)),
            datetime(2026, 11, 4, 12, 0, 0),
        )

    def test_local_now_is_naive_shanghai_wall_clock(self):
        expected = datetime.now(SHANGHAI_OFFSET).replace(tzinfo=None)
        self.assertLess(abs((local_now() - expected).total_seconds()), 5)

    def test_now_shanghai_is_the_shared_platform_clock(self):
        expected = datetime.now(SHANGHAI_OFFSET).replace(tzinfo=None)
        self.assertLess(abs((now_shanghai() - expected).total_seconds()), 5)
        self.assertIsNone(now_shanghai().tzinfo)


class _FakeResponse:
    def __init__(self, error=None):
        self.error = error
        self.raise_called = False

    def raise_for_status(self):
        self.raise_called = True
        if self.error is not None:
            raise self.error


class _FakeHttpx:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def post(self, url, json=None, timeout=None):
        self.calls.append({"url": url, "json": json, "timeout": timeout})
        return self.response


class WebhookSenderTest(unittest.TestCase):
    def test_posts_json_with_five_second_timeout(self):
        response = _FakeResponse()
        fake = _FakeHttpx(response)
        with mock.patch.dict(sys.modules, {"httpx": fake}):
            default_webhook_sender("https://hook.example.invalid/alert", {"text": "x"})
        self.assertEqual(
            fake.calls,
            [
                {
                    "url": "https://hook.example.invalid/alert",
                    "json": {"text": "x"},
                    "timeout": 5,
                }
            ],
        )
        self.assertTrue(response.raise_called)

    def test_rejected_payload_propagates(self):
        fake = _FakeHttpx(_FakeResponse(RuntimeError("status 500")))
        with mock.patch.dict(sys.modules, {"httpx": fake}):
            with self.assertRaises(RuntimeError):
                default_webhook_sender("https://hook.example.invalid/alert", {})

    def test_missing_httpx_is_reported(self):
        with mock.patch.dict(sys.modules, {"httpx": None}):
            with self.assertRaises(ImportError):
                default_webhook_sender("https://hook.example.invalid/alert", {})


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class AlertServiceEvaluationTest(unittest.TestCase):
    def _service(self, db=None, sender=None, url=""):
        from app.services.alert_service import AlertService

        return AlertService(
            db or FakeDb(),
            sender=sender,
            webhook_url=url,
            now=lambda: datetime(2026, 11, 4, 12, 0, 0),
        )

    def test_fires_and_persists_an_open_record(self):
        db = FakeDb()

        outcome = self._service(db).evaluate_rule(
            _rule_row(), 600, "hospital:1", 1, datetime(2026, 11, 4, 12, 0, 0)
        )

        self.assertTrue(outcome.fired)
        self.assertEqual(outcome.reason, "fired")
        self.assertEqual(outcome.rule_id, 1)
        self.assertEqual(outcome.fingerprint, fingerprint(1, "hospital:1"))
        self.assertFalse(outcome.webhook_sent)
        self.assertIsNone(outcome.webhook_error)
        self.assertEqual(len(db.added), 1)
        record = db.added[0]
        self.assertEqual(outcome.record_id, record.id)
        self.assertEqual(record.rule_id, 1)
        self.assertEqual(record.fingerprint, fingerprint(1, "hospital:1"))
        self.assertEqual(record.severity, "P0")
        self.assertEqual(record.status, "OPEN")
        self.assertEqual(record.created_at, datetime(2026, 11, 4, 12, 0, 0))
        self.assertIn("链路延迟", record.title)
        self.assertIn("value=600", record.content)
        self.assertEqual(db.commits, 1)

    def test_disabled_rule_does_not_fire(self):
        db = FakeDb()

        outcome = self._service(db).evaluate_rule(_rule_row(enabled=False), 600, "hospital:1")

        self.assertFalse(outcome.fired)
        self.assertEqual(outcome.reason, "rule is disabled")
        self.assertEqual(db.added, [])

    def test_value_below_threshold_does_not_fire(self):
        db = FakeDb()

        outcome = self._service(db).evaluate_rule(_rule_row(), 300, "hospital:1")

        self.assertFalse(outcome.fired)
        self.assertEqual(outcome.reason, "threshold or consecutive periods not satisfied")
        # The latest-record lookup is skipped while the value does not breach.
        self.assertEqual(db.query_obj.filters, [])

    def test_consecutive_periods_are_enforced(self):
        db = FakeDb()
        rule = _rule_row(consecutive_periods=3)
        service = self._service(db)

        self.assertFalse(service.evaluate_rule(rule, 600, "hospital:1", 2).fired)
        self.assertEqual(db.added, [])
        self.assertTrue(service.evaluate_rule(rule, 600, "hospital:1", 3).fired)

    def test_cooldown_blocks_a_repeat_for_the_same_fingerprint(self):
        matching = SimpleNamespace(
            id=90,
            fingerprint=fingerprint(1, "hospital:1"),
            created_at=datetime(2026, 11, 4, 11, 50, 0),
            status="OPEN",
        )
        # Newer, but for another hospital: ``latest_record`` must filter it
        # out instead of letting it satisfy (or bypass) the cooldown.
        other = SimpleNamespace(
            id=91,
            fingerprint=fingerprint(1, "hospital:2"),
            created_at=datetime(2026, 11, 4, 11, 58, 0),
            status="OPEN",
        )
        db = FakeDb([other, matching])

        outcome = self._service(db).evaluate_rule(
            _rule_row(), 600, "hospital:1", 1, datetime(2026, 11, 4, 12, 0, 0)
        )

        self.assertFalse(outcome.fired)
        self.assertEqual(outcome.reason, "cooldown active for this fingerprint")
        self.assertEqual(db.added, [])
        self.assertEqual(len(db.query_obj.filters), 1)
        # The fake applied the fingerprint predicate, not just recorded it.
        self.assertEqual(db.query_obj.rows, [matching])

    def test_cooldown_applies_even_when_the_previous_alert_was_closed(self):
        record = SimpleNamespace(
            id=90,
            fingerprint=fingerprint(1, "hospital:1"),
            created_at=datetime(2026, 11, 4, 11, 55, 0),
            status="CLOSED",
        )
        db = FakeDb([record])

        outcome = self._service(db).evaluate_rule(_rule_row(), 600, "hospital:1")

        self.assertFalse(outcome.fired)
        self.assertEqual(outcome.reason, "cooldown active for this fingerprint")

    def test_fires_once_the_cooldown_has_elapsed(self):
        record = SimpleNamespace(
            id=90,
            fingerprint=fingerprint(1, "hospital:1"),
            created_at=datetime(2026, 11, 4, 11, 29, 0),
            status="OPEN",
        )
        db = FakeDb([record])

        outcome = self._service(db).evaluate_rule(_rule_row(), 600, "hospital:1")

        self.assertTrue(outcome.fired)
        self.assertEqual(len(db.added), 1)

    def test_different_dimensions_do_not_share_a_cooldown(self):
        record = SimpleNamespace(
            id=90,
            fingerprint=fingerprint(1, "hospital:2"),
            created_at=datetime(2026, 11, 4, 11, 55, 0),
            status="OPEN",
        )
        db = FakeDb([record])

        outcome = self._service(db).evaluate_rule(_rule_row(), 600, "hospital:1")

        # Regression: a recent record for another fingerprint must not trip
        # the cooldown of ``hospital:1``.
        self.assertTrue(outcome.fired)
        self.assertEqual(db.query_obj.rows, [])
        self.assertEqual(db.added[0].fingerprint, fingerprint(1, "hospital:1"))

    def test_aware_now_is_stored_as_shanghai_wall_clock(self):
        db = FakeDb()

        self._service(db).evaluate_rule(
            _rule_row(), 600, "hospital:1", 1, datetime(2026, 11, 4, 4, 0, tzinfo=timezone.utc)
        )

        self.assertEqual(db.added[0].created_at, datetime(2026, 11, 4, 12, 0, 0))

    def test_default_now_is_the_service_clock(self):
        db = FakeDb()

        self._service(db).evaluate_rule(_rule_row(), 600, "hospital:1")

        self.assertEqual(db.added[0].created_at, datetime(2026, 11, 4, 12, 0, 0))

    def test_webhook_url_falls_back_to_settings(self):
        from app.services import alert_service as module

        calls = []
        settings = SimpleNamespace(alert_webhook_url="https://hook.example.invalid/alert")
        with mock.patch.object(module, "get_settings", return_value=settings):
            service = module.AlertService(
                FakeDb(),
                sender=lambda url, payload: calls.append((url, payload)),
                webhook_url=None,
                now=lambda: datetime(2026, 11, 4, 12, 0, 0),
            )
            outcome = service.evaluate_rule(_rule_row(), 600, "hospital:1")

        self.assertTrue(outcome.webhook_sent)
        self.assertEqual(calls[0][0], "https://hook.example.invalid/alert")

    def test_webhook_url_is_resolved_once_at_construction(self):
        from app.services import alert_service as module

        settings = SimpleNamespace(alert_webhook_url="https://hook.example.invalid/first")
        calls = []
        with mock.patch.object(module, "get_settings", return_value=settings) as getter:
            service = module.AlertService(
                FakeDb(),
                sender=lambda url, payload: calls.append(url),
                webhook_url=None,
                now=lambda: datetime(2026, 11, 4, 12, 0, 0),
            )
            # A settings change after construction must not be re-read on
            # every evaluation.
            settings.alert_webhook_url = "https://hook.example.invalid/second"
            outcome = service.evaluate_rule(_rule_row(), 600, "hospital:1")

        self.assertTrue(outcome.webhook_sent)
        self.assertEqual(calls, ["https://hook.example.invalid/first"])
        self.assertEqual(getter.call_count, 1)

    def test_webhook_is_sent_through_the_injected_sender(self):
        calls = []
        db = FakeDb()
        service = self._service(
            db,
            sender=lambda url, payload: calls.append((url, payload)),
            url="https://hook.example.invalid/alert",
        )

        outcome = service.evaluate_rule(_rule_row(), 600, "hospital:1")

        self.assertTrue(outcome.webhook_sent)
        self.assertIsNone(outcome.webhook_error)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][0], "https://hook.example.invalid/alert")
        self.assertEqual(calls[0][1]["msgtype"], "text")
        self.assertIn("链路延迟", calls[0][1]["text"]["content"])

    def test_webhook_failure_never_rolls_back_or_raises(self):
        def boom(url, payload):
            raise RuntimeError("connection refused")

        db = FakeDb()
        service = self._service(
            db, sender=boom, url="https://hook.example.invalid/alert"
        )

        outcome = service.evaluate_rule(_rule_row(), 600, "hospital:1")

        self.assertTrue(outcome.fired)
        self.assertFalse(outcome.webhook_sent)
        self.assertIn("connection refused", outcome.webhook_error)
        self.assertFalse(db.rolled_back)
        self.assertEqual(len(db.added), 1)
        self.assertEqual(db.added[0].status, "OPEN")
        self.assertEqual(outcome.record_id, db.added[0].id)

    def test_inapp_channel_skips_the_webhook(self):
        calls = []
        service = self._service(
            FakeDb(),
            sender=lambda url, payload: calls.append((url, payload)),
            url="https://hook.example.invalid/alert",
        )

        outcome = service.evaluate_rule(_rule_row(channel="inapp"), 600, "hospital:1")

        self.assertTrue(outcome.fired)
        self.assertFalse(outcome.webhook_sent)
        self.assertEqual(calls, [])

    def test_missing_webhook_url_skips_the_sender(self):
        def fail(url, payload):
            raise AssertionError("sender must not be called without a url")

        outcome = self._service(FakeDb(), sender=fail, url="").evaluate_rule(
            _rule_row(), 600, "hospital:1"
        )

        self.assertTrue(outcome.fired)
        self.assertFalse(outcome.webhook_sent)
        self.assertIsNone(outcome.webhook_error)

    def test_persistence_failure_rolls_back_and_raises(self):
        from app.core.exceptions import DataPlatformError

        class BrokenDb(FakeDb):
            def commit(self):
                raise RuntimeError("database is locked")

        db = BrokenDb()

        with self.assertRaises(DataPlatformError) as context:
            self._service(db).evaluate_rule(_rule_row(), 600, "hospital:1")

        self.assertEqual(context.exception.code, 500)
        self.assertIn("failed to persist alert record", context.exception.message)
        self.assertTrue(db.rolled_back)

    def test_unpersisted_rule_is_rejected(self):
        with self.assertRaises(ValueError):
            self._service(FakeDb()).evaluate_rule(_rule_row(id=None), 600, "hospital:1")

    def test_unknown_rule_operator_surfaces(self):
        with self.assertRaises(ValueError) as context:
            self._service(FakeDb()).evaluate_rule(_rule_row(operator="!="), 600, "hospital:1")
        self.assertIn("unknown operator", str(context.exception))

    def test_negative_breach_count_is_rejected(self):
        with self.assertRaises(ValueError):
            self._service(FakeDb()).evaluate_rule(
                _rule_row(), 600, "hospital:1", -1
            )

    def test_non_numeric_value_is_rejected(self):
        with self.assertRaises(ValueError):
            self._service(FakeDb()).evaluate_rule(
                _rule_row(), "not-a-number", "hospital:1"
            )


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class AlertServiceRuleCrudTest(unittest.TestCase):
    def _service(self, db):
        from app.services.alert_service import AlertService

        return AlertService(db, webhook_url="")

    def test_create_rule_normalises_and_persists(self):
        db = FakeDb()

        entity = self._service(db).create_rule(
            name=" lag ",
            metric_type="pipeline_lag",
            threshold=300,
            severity="p0",
            channel="WEBHOOK",
        )

        self.assertEqual(db.commits, 1)
        self.assertEqual(db.added, [entity])
        self.assertEqual(entity.id, 500)
        self.assertEqual(entity.name, "lag")
        self.assertEqual(entity.metric_type, "PIPELINE_LAG")
        self.assertEqual(entity.operator, ">")
        self.assertEqual(entity.threshold, 300.0)
        self.assertEqual(entity.window_minutes, 5)
        self.assertEqual(entity.consecutive_periods, 1)
        self.assertEqual(entity.severity, "P0")
        self.assertEqual(entity.channel, "webhook")
        self.assertEqual(entity.cooldown_minutes, 30)
        self.assertTrue(entity.enabled)

    def test_create_rule_accepts_a_zero_cooldown(self):
        entity = self._service(FakeDb()).create_rule(
            name="lag", metric_type="PIPELINE_LAG", threshold=1, cooldown_minutes=0
        )
        self.assertEqual(entity.cooldown_minutes, 0)

    def test_create_rule_rejects_unknown_metric_type(self):
        db = FakeDb()
        with self.assertRaises(ValueError):
            self._service(db).create_rule(
                name="lag", metric_type="ROW_COUNT", threshold=1
            )
        self.assertEqual(db.added, [])

    def test_create_rule_rejects_unknown_operator(self):
        with self.assertRaises(ValueError):
            self._service(FakeDb()).create_rule(
                name="lag", metric_type="PIPELINE_LAG", threshold=1, operator="!="
            )

    def test_create_rule_rejects_unknown_severity(self):
        with self.assertRaises(ValueError):
            self._service(FakeDb()).create_rule(
                name="lag", metric_type="PIPELINE_LAG", threshold=1, severity="P9"
            )

    def test_create_rule_rejects_unknown_channel(self):
        with self.assertRaises(ValueError):
            self._service(FakeDb()).create_rule(
                name="lag", metric_type="PIPELINE_LAG", threshold=1, channel="email"
            )

    def test_create_rule_rejects_blank_name(self):
        with self.assertRaises(ValueError):
            self._service(FakeDb()).create_rule(
                name="   ", metric_type="PIPELINE_LAG", threshold=1
            )

    def test_create_rule_rejects_non_finite_threshold(self):
        with self.assertRaises(ValueError):
            self._service(FakeDb()).create_rule(
                name="lag", metric_type="PIPELINE_LAG", threshold=float("inf")
            )

    def test_create_rule_rejects_a_zero_window(self):
        with self.assertRaises(ValueError):
            self._service(FakeDb()).create_rule(
                name="lag", metric_type="PIPELINE_LAG", threshold=1, window_minutes=0
            )

    def test_update_rule_merges_and_validates(self):
        db = FakeDb()
        entity = _rule_row()

        self._service(db).update_rule(entity, threshold=600, severity="p1")

        self.assertEqual(entity.threshold, 600.0)
        self.assertEqual(entity.severity, "P1")
        self.assertEqual(db.commits, 1)

    def test_update_rejects_unknown_fields(self):
        with self.assertRaises(ValueError):
            self._service(FakeDb()).update_rule(_rule_row(), owner="me")

    def test_update_rejects_null_required_fields(self):
        with self.assertRaises(ValueError):
            self._service(FakeDb()).update_rule(_rule_row(), name=None)

    def test_update_revalidates_the_merged_configuration(self):
        with self.assertRaises(ValueError):
            self._service(FakeDb()).update_rule(_rule_row(), metric_type="ROW_COUNT")

    def test_delete_rule_keeps_alert_history(self):
        record = _alert_row()
        db = FakeDb([record])
        entity = _rule_row()

        self._service(db).delete_rule(entity)

        self.assertEqual(db.deleted, [entity])
        self.assertEqual(db.records, [record])
        self.assertEqual(db.commits, 1)


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class AlertServiceLifecycleTest(unittest.TestCase):
    def _service(self, db):
        from app.services.alert_service import AlertService

        return AlertService(
            db, webhook_url="", now=lambda: datetime(2026, 11, 4, 12, 0, 0)
        )

    def test_ack_sets_status_and_timestamp(self):
        db = FakeDb()
        row = _alert_row()

        result = self._service(db).ack_alert(row)

        self.assertIs(result, row)
        self.assertEqual(row.status, "ACKED")
        self.assertEqual(row.ack_at, datetime(2026, 11, 4, 12, 0, 0))
        self.assertEqual(db.commits, 1)

    def test_ack_is_idempotent(self):
        db = FakeDb()
        row = _alert_row(status="ACKED", ack_at=datetime(2026, 11, 4, 11, 0, 0))

        self._service(db).ack_alert(row)

        self.assertEqual(row.ack_at, datetime(2026, 11, 4, 11, 0, 0))
        self.assertEqual(db.commits, 0)

    def test_ack_of_a_closed_alert_is_rejected(self):
        with self.assertRaises(ValueError):
            self._service(FakeDb()).ack_alert(_alert_row(status="CLOSED"))

    def test_close_sets_status_and_timestamp(self):
        db = FakeDb()
        row = _alert_row(status="ACKED")

        self._service(db).close_alert(row)

        self.assertEqual(row.status, "CLOSED")
        self.assertEqual(row.closed_at, datetime(2026, 11, 4, 12, 0, 0))
        self.assertEqual(db.commits, 1)

    def test_close_is_idempotent(self):
        db = FakeDb()
        row = _alert_row(status="CLOSED", closed_at=datetime(2026, 11, 4, 11, 0, 0))

        self._service(db).close_alert(row)

        self.assertEqual(row.closed_at, datetime(2026, 11, 4, 11, 0, 0))
        self.assertEqual(db.commits, 0)

    def test_lifecycle_commit_failure_rolls_back(self):
        from app.core.exceptions import DataPlatformError

        class BrokenDb(FakeDb):
            def commit(self):
                raise RuntimeError("database is locked")

        db = BrokenDb()
        with self.assertRaises(DataPlatformError) as context:
            self._service(db).ack_alert(_alert_row())

        self.assertEqual(context.exception.code, 500)
        self.assertTrue(db.rolled_back)


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class AlertModelDefaultsTest(unittest.TestCase):
    def test_created_at_defaults_to_the_shanghai_clock(self):
        from app.models.metadata import DpAlertRecord

        default = DpAlertRecord.__table__.c.created_at.default
        self.assertIsNotNone(default)
        # SQLAlchemy either keeps the zero-argument callable or wraps it in a
        # context-aware one; accept both shapes.
        try:
            value = default.arg()
        except TypeError:
            value = default.arg(None)
        expected = datetime.now(SHANGHAI_OFFSET).replace(tzinfo=None)
        self.assertIsNone(value.tzinfo)
        self.assertLess(abs((value - expected).total_seconds()), 5)


@unittest.skipUnless(HAS_STACK, "backend dependencies not installed")
class AlertRoutesTest(unittest.TestCase):
    def test_routes_cover_the_design_surface(self):
        from app.api import alerts

        routes = {}
        for route in alerts.router.routes:
            routes.setdefault(route.path, set()).update(route.methods)

        # Starlette adds HEAD to every GET route, so assert membership rather
        # than equality (same fix as the quality route test).
        self.assertIn("GET", routes["/alerts"])
        self.assertTrue({"GET", "POST"} <= routes["/alerts/rules"])
        self.assertTrue({"PUT", "DELETE"} <= routes["/alerts/rules/{rule_id}"])
        self.assertEqual(routes["/alerts/{alert_id}/ack"], {"POST"})
        self.assertEqual(routes["/alerts/{alert_id}/close"], {"POST"})

    def test_rule_view_exposes_configuration(self):
        from app.api import alerts

        view = alerts._rule_view(_rule_row())
        self.assertEqual(
            set(view),
            {
                "id",
                "name",
                "metric_type",
                "operator",
                "threshold",
                "window_minutes",
                "consecutive_periods",
                "severity",
                "channel",
                "cooldown_minutes",
                "enabled",
            },
        )
        self.assertEqual(view["metric_type"], "PIPELINE_LAG")
        self.assertEqual(view["cooldown_minutes"], 30)

    def test_alert_view_exposes_lifecycle(self):
        from app.api import alerts

        view = alerts._alert_view(
            _alert_row(ack_at=datetime(2026, 11, 4, 12, 5, 0), status="ACKED")
        )
        self.assertEqual(
            set(view),
            {
                "id",
                "rule_id",
                "fingerprint",
                "severity",
                "title",
                "content",
                "status",
                "created_at",
                "ack_at",
                "closed_at",
            },
        )
        self.assertEqual(view["created_at"], "2026-11-04T12:00:00")
        self.assertEqual(view["ack_at"], "2026-11-04T12:05:00")
        self.assertIsNone(view["closed_at"])

    def test_list_alerts_filters_and_limits(self):
        from app.api import alerts

        db = FakeDb([_alert_row()])

        result = alerts.list_alerts(
            status="open", severity="p0", rule_id=1, limit=25, db=db
        )

        self.assertEqual(len(db.query_obj.filters), 3)
        self.assertTrue(db.query_obj.order_by_args)
        self.assertEqual(db.query_obj.limit_value, 25)
        self.assertEqual(result["data"][0]["id"], 7)

    def test_list_alerts_rejects_an_unknown_status(self):
        from fastapi import HTTPException

        from app.api import alerts

        with self.assertRaises(HTTPException) as context:
            alerts.list_alerts(status="DONE", db=FakeDb())
        self.assertEqual(context.exception.status_code, 400)

    def test_list_alerts_rejects_an_unknown_severity(self):
        from fastapi import HTTPException

        from app.api import alerts

        with self.assertRaises(HTTPException) as context:
            alerts.list_alerts(severity="P9", db=FakeDb())
        self.assertEqual(context.exception.status_code, 400)

    def test_missing_alert_is_404(self):
        from fastapi import HTTPException

        from app.api import alerts

        with self.assertRaises(HTTPException) as context:
            alerts._get_alert(FakeDb(), 7)
        self.assertEqual(context.exception.status_code, 404)

    def test_missing_rule_is_404(self):
        from fastapi import HTTPException

        from app.api import alerts

        with self.assertRaises(HTTPException) as context:
            alerts._get_rule(FakeDb(), 3)
        self.assertEqual(context.exception.status_code, 404)

    def test_ack_of_an_open_alert_updates_it(self):
        from app.api import alerts

        db = FakeDb()
        db.get_rows[7] = _alert_row()

        result = alerts.ack_alert(7, db=db)

        self.assertEqual(db.get_rows[7].status, "ACKED")
        self.assertEqual(result["data"]["status"], "ACKED")

    def test_ack_of_a_closed_alert_returns_409(self):
        from fastapi import HTTPException

        from app.api import alerts

        db = FakeDb()
        db.get_rows[7] = _alert_row(status="CLOSED")

        with self.assertRaises(HTTPException) as context:
            alerts.ack_alert(7, db=db)
        self.assertEqual(context.exception.status_code, 409)

    def test_close_of_an_open_alert_updates_it(self):
        from app.api import alerts

        db = FakeDb()
        db.get_rows[7] = _alert_row()

        result = alerts.close_alert(7, db=db)

        self.assertEqual(db.get_rows[7].status, "CLOSED")
        self.assertEqual(result["data"]["status"], "CLOSED")

    def test_list_rules_returns_views(self):
        from app.api import alerts

        db = FakeDb()
        db.query_obj.rows = [_rule_row()]

        result = alerts.list_alert_rules(db=db)

        self.assertEqual(result["data"][0]["id"], 1)

    def test_create_rule_returns_the_rule_view(self):
        from app.api import alerts
        from app.schemas.alert import AlertRuleCreate

        payload = AlertRuleCreate(
            name="链路延迟", metric_type="PIPELINE_LAG", threshold=300
        )
        with mock.patch.object(alerts, "AlertService") as service:
            service.return_value.create_rule.return_value = _rule_row(id=9)
            result = alerts.create_alert_rule(payload, db=FakeDb())

        self.assertEqual(result["data"]["id"], 9)
        service.return_value.create_rule.assert_called_once_with(**payload.model_dump())

    def test_update_rule_forwards_the_changes(self):
        from app.api import alerts
        from app.schemas.alert import AlertRuleUpdate

        db = FakeDb()
        db.get_rows[1] = _rule_row()
        payload = AlertRuleUpdate(threshold=600, severity="p0")
        with mock.patch.object(alerts, "AlertService") as service:
            service.return_value.update_rule.return_value = _rule_row(
                threshold=600.0, severity="P0"
            )
            result = alerts.update_alert_rule(1, payload, db=db)

        _, kwargs = service.return_value.update_rule.call_args
        self.assertEqual(kwargs, {"threshold": 600.0, "severity": "p0"})
        self.assertEqual(result["data"]["threshold"], 600.0)
        self.assertEqual(result["data"]["severity"], "P0")

    def test_delete_rule_returns_the_id(self):
        from app.api import alerts

        db = FakeDb()
        db.get_rows[1] = _rule_row()
        with mock.patch.object(alerts, "AlertService") as service:
            result = alerts.delete_alert_rule(1, db=db)

        service.return_value.delete_rule.assert_called_once()
        self.assertEqual(result, {"code": 0, "message": "ok", "data": {"deleted": 1}})


if __name__ == "__main__":
    unittest.main()
