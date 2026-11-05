"""APScheduler entry point for periodic incremental syncs.

Importing this module never starts anything: the scheduler is built and started
only by :func:`start_scheduler`, which ``app.main.create_app`` calls when
``DP_ENABLE_SCHEDULER=true`` (or by an operator running the scheduler manually).
"""

import logging

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger

from app.core.config import get_settings
from app.core.db import SessionLocal
from app.models.metadata import DataSource, SyncTask
from app.services.datasource_service import DatasourceService
from app.services.sync_service import SyncService
from etl.adapters.factory import build_event_bus
from etl.adapters.mysql_source_repository import MySQLSourceRepository

logger = logging.getLogger(__name__)

SCHEDULER_TIMEZONE = "Asia/Shanghai"

_scheduler: BackgroundScheduler | None = None


def run_task(task_id: int) -> None:
    """Run one incremental sync for ``task_id`` outside the API request path."""
    db = SessionLocal()
    publisher = None
    try:
        task = db.get(SyncTask, task_id)
        if task is None:
            logger.warning("scheduled sync task %s no longer exists", task_id)
            return
        datasource = db.get(DataSource, task.datasource_id)
        if datasource is None:
            logger.warning(
                "scheduled sync task %s references missing datasource %s",
                task_id,
                task.datasource_id,
            )
            return
        settings = get_settings()
        source = MySQLSourceRepository(
            DatasourceService(db, settings.dp_secret_key).url_for(datasource)
        )
        publisher = build_event_bus(
            settings.event_bus, settings.kafka_bootstrap, settings.kafka_consumer_group
        )
        SyncService(db, topic_prefix=settings.kafka_topic_prefix).run(
            task_id, "INCREMENTAL", source, publisher
        )
    finally:
        try:
            if publisher is not None:
                # The factory returns a producer/consumer pair: flush the ODS
                # events and release the unused consumer group membership.
                publisher.flush()
                publisher.close()
        finally:
            db.close()


def build_scheduler() -> BackgroundScheduler:
    scheduler = BackgroundScheduler(timezone=SCHEDULER_TIMEZONE)
    db = SessionLocal()
    try:
        jobs = [
            (task.id, task.cron)
            for task in db.query(SyncTask).filter(SyncTask.enabled.is_(True)).all()
        ]
    finally:
        db.close()
    for task_id, cron in jobs:
        try:
            trigger = CronTrigger.from_crontab(cron, timezone=SCHEDULER_TIMEZONE)
        except ValueError:
            # One malformed cron must not stop the application from starting;
            # the task stays visible in the API so it can be corrected.
            logger.warning("sync task %s has an invalid cron %r; skipped", task_id, cron)
            continue
        scheduler.add_job(
            run_task,
            trigger,
            args=[task_id],
            id=f"sync-{task_id}",
            replace_existing=True,
        )
    return scheduler


def start_scheduler() -> BackgroundScheduler:
    global _scheduler
    _scheduler = build_scheduler()
    _scheduler.start()
    return _scheduler


def refresh_job(task_id: int, cron: str, enabled: bool) -> None:
    """Create, update or remove a sync job after the task is changed via API."""
    scheduler = _scheduler
    if scheduler is None:
        return
    job_id = f"sync-{task_id}"
    if not enabled:
        if scheduler.get_job(job_id) is not None:
            scheduler.remove_job(job_id)
        return
    try:
        trigger = CronTrigger.from_crontab(cron, timezone=SCHEDULER_TIMEZONE)
    except ValueError:
        logger.warning("sync task %s has an invalid cron %r; job not scheduled", task_id, cron)
        return
    scheduler.add_job(run_task, trigger, args=[task_id], id=job_id, replace_existing=True)


def remove_job(task_id: int) -> None:
    """Remove a sync job when the task is deleted."""
    scheduler = _scheduler
    if scheduler is None:
        return
    job_id = f"sync-{task_id}"
    if scheduler.get_job(job_id) is not None:
        scheduler.remove_job(job_id)
