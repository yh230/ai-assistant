import re
from pathlib import Path

from etl.ports.olap_store import OlapStore

_TEMPLATE = re.compile(r"\{\{(\w+)\}\}")


def render_sql(sql: str, params: dict[str, str]) -> str:
    """Substitute ``{{name}}`` placeholders with the supplied values.

    A placeholder without a matching parameter raises :class:`ValueError`
    instead of being left in the statement, so a scheduling mistake fails
    before anything is executed.
    """

    def replace(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in params:
            raise ValueError(f"missing SQL parameter: {key}")
        return params[key]

    return _TEMPLATE.sub(replace, sql)


class TransformRunner:
    """Run the SQL files of one warehouse stage (``ddl`` / ``dwd`` / ``ads``).

    Files are executed in filename order; each file holds one statement and
    may use ``{{ds}}`` style placeholders.  The DDL stage has no placeholders
    and is executed as-is.
    """

    def __init__(self, olap: OlapStore, sql_root: Path):
        self.olap = olap
        self.sql_root = Path(sql_root)

    def run_stage(self, stage: str, params: dict[str, str]) -> list[str]:
        stage_root = self.sql_root / stage
        if not stage_root.is_dir():
            raise FileNotFoundError(f"unknown SQL stage: {stage}")
        executed: list[str] = []
        for path in sorted(stage_root.glob("*.sql")):
            self.olap.execute(render_sql(path.read_text(encoding="utf-8"), params))
            executed.append(path.name)
        return executed
