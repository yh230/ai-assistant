"""Manual warehouse transform CLI.

Run with ``python -m etl.transform_main --stage ddl`` (then ``--stage dwd``
and ``--stage ads`` with ``--ds YYYY-MM-DD``).  The DDL file is split into
single statements because clickhouse-connect ``command`` accepts one at a
time.  This is a manual acceptance entry point: V1 does not schedule the
transforms.
"""

import argparse
import logging
from datetime import date
from pathlib import Path

from etl.transform import TransformRunner

logger = logging.getLogger(__name__)

SQL_ROOT = Path(__file__).resolve().parent / "sql"
STAGES = ("ddl", "dwd", "ads")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m etl.transform_main",
        description="Run the ClickHouse DDL, DWD or ADS stage.",
    )
    parser.add_argument("--stage", required=True, choices=STAGES)
    parser.add_argument(
        "--ds",
        help="business date (YYYY-MM-DD); required for the dwd and ads stages",
    )
    return parser


def validate_args(stage: str, ds: str | None) -> dict[str, str]:
    """Return the render parameters for ``stage`` or raise ``ValueError``."""
    if stage not in STAGES:
        raise ValueError(f"unknown transform stage: {stage}")
    if stage == "ddl":
        return {}
    if not ds:
        raise ValueError("--ds is required for the dwd and ads stages")
    try:
        date.fromisoformat(ds)
    except ValueError as exc:
        raise ValueError(f"--ds must be YYYY-MM-DD: {ds}") from exc
    return {"ds": ds}


def run(runner: TransformRunner, stage: str, ds: str | None) -> list[str]:
    params = validate_args(stage, ds)
    if stage == "ddl":
        return runner.run_ddl(params)
    return runner.run_stage(stage, params)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    # Imported lazily so the parser and stage helpers stay importable without
    # the FastAPI/pydantic settings dependency set (matching consumer_main).
    from app.core.config import get_settings
    from etl.adapters.factory import build_olap_store

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    settings = get_settings()
    if settings.olap_store == "memory":
        logger.warning(
            "OLAP_STORE=memory: statements are recorded in memory and not sent "
            "to ClickHouse; set OLAP_STORE=clickhouse for a real transform"
        )
    olap = build_olap_store(
        settings.olap_store, settings.clickhouse_url, settings.clickhouse_database
    )
    executed = run(TransformRunner(olap, SQL_ROOT), args.stage, args.ds)
    logger.info("stage %s executed: %s", args.stage, ", ".join(executed) or "(none)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
