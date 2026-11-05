import re
from pathlib import Path

from etl.ports.olap_store import OlapStore

_TEMPLATE = re.compile(r"\{\{(\w+)\}\}")
_COMMENT = re.compile(r"--[^\n]*|/\*.*?\*/", re.DOTALL)


def _has_sql_content(statement: str) -> bool:
    return bool(_COMMENT.sub("", statement).strip())


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


def split_statements(sql: str) -> list[str]:
    """Split a SQL file into statements on ``;``.

    The scanner understands the constructs that appear in the DDL file:
    single-quoted string literals (with backslash escapes), double-quoted and
    backticked identifiers, ``--`` line comments and ``/* */`` block comments.
    A semicolon inside any of them does not terminate a statement, which is
    enough for the warehouse scripts without pulling in a full SQL parser.
    """
    statements: list[str] = []
    current: list[str] = []
    index = 0
    length = len(sql)
    while index < length:
        char = sql[index]
        nxt = sql[index + 1] if index + 1 < length else ""
        if char == "-" and nxt == "-":
            # Keep the comment text; the semicolon after it is not a boundary.
            end = sql.find("\n", index)
            if end == -1:
                current.append(sql[index:])
                break
            current.append(sql[index : end + 1])
            index = end + 1
            continue
        if char == "/" and nxt == "*":
            end = sql.find("*/", index + 2)
            end = length if end == -1 else end + 2
            current.append(sql[index:end])
            index = end
            continue
        if char in ("'", '"', "`"):
            quote = char
            chunk = [char]
            index += 1
            while index < length:
                char = sql[index]
                chunk.append(char)
                if char == "\\" and index + 1 < length:
                    chunk.append(sql[index + 1])
                    index += 2
                    continue
                index += 1
                if char == quote:
                    # A doubled quote is an escaped quote inside the literal.
                    if index < length and sql[index] == quote:
                        chunk.append(sql[index])
                        index += 1
                        continue
                    break
            current.append("".join(chunk))
            continue
        if char == ";":
            statement = "".join(current).strip()
            if statement and _has_sql_content(statement):
                statements.append(statement)
            current = []
            index += 1
            continue
        current.append(char)
        index += 1
    tail = "".join(current).strip()
    if tail and _has_sql_content(tail):
        statements.append(tail)
    return statements


class TransformRunner:
    """Run the SQL files of one warehouse stage (``ddl`` / ``dwd`` / ``ads``).

    Files are executed in filename order; each file may hold several ``;``
    separated statements and may use ``{{ds}}`` style placeholders.  The DDL
    stage has no placeholders and is executed through :meth:`run_ddl`, which
    splits the multi-statement file because the clickhouse-connect
    ``command`` API accepts one statement at a time.
    """

    def __init__(self, olap: OlapStore, sql_root: Path):
        self.olap = olap
        self.sql_root = Path(sql_root)

    def run_stage(self, stage: str, params: dict[str, str]) -> list[str]:
        stage_root = self._stage_root(stage)
        executed: list[str] = []
        for path in sorted(stage_root.glob("*.sql")):
            self.olap.execute(render_sql(path.read_text(encoding="utf-8"), params))
            executed.append(path.name)
        return executed

    def run_ddl(self, params: dict[str, str] | None = None) -> list[str]:
        """Execute every statement of every ``ddl/*.sql`` file, in order."""
        stage_root = self._stage_root("ddl")
        executed: list[str] = []
        for path in sorted(stage_root.glob("*.sql")):
            content = render_sql(path.read_text(encoding="utf-8"), params or {})
            for statement in split_statements(content):
                self.olap.execute(statement)
            executed.append(path.name)
        return executed

    def _stage_root(self, stage: str) -> Path:
        stage_root = self.sql_root / stage
        if not stage_root.is_dir():
            raise FileNotFoundError(f"unknown SQL stage: {stage}")
        return stage_root
