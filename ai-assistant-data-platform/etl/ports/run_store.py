from typing import Any, Protocol


class RunStore(Protocol):
    def start_run(self, task_id: int, run_type: str) -> int: ...

    def checkpoint(
        self, run_id: int, rows_read: int, rows_written: int, cursor: dict[str, Any]
    ) -> None: ...

    def finish_run(
        self,
        run_id: int,
        status: str,
        rows_read: int,
        rows_written: int,
        error: str | None = None,
    ) -> None: ...
