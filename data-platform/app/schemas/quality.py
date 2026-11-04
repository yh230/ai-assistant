from typing import Any

from pydantic import BaseModel, Field

# ``rule_type`` and ``severity`` are plain strings here on purpose: the service
# is the single validator and reports an unsupported value as HTTP 400, whereas
# ``Literal`` fields would surface as a 422.


class QualityRuleCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    target_table: str = Field(min_length=1, max_length=100)
    rule_type: str = Field(min_length=1, max_length=30)
    column_name: str | None = Field(default=None, max_length=100)
    params: dict[str, Any] = Field(default_factory=dict)
    threshold: float = 1.0
    severity: str = Field(default="P1", min_length=1, max_length=10)
    cron: str = Field(default="0 * * * *", min_length=1, max_length=50)
    enabled: bool = True


class QualityRuleUpdate(BaseModel):
    """Partial update; only fields present in the request are applied."""

    name: str | None = Field(default=None, min_length=1, max_length=100)
    target_table: str | None = Field(default=None, min_length=1, max_length=100)
    rule_type: str | None = Field(default=None, min_length=1, max_length=30)
    column_name: str | None = Field(default=None, max_length=100)
    params: dict[str, Any] | None = None
    threshold: float | None = None
    severity: str | None = Field(default=None, min_length=1, max_length=10)
    cron: str | None = Field(default=None, min_length=1, max_length=50)
    enabled: bool | None = None
