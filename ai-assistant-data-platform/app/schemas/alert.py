from pydantic import BaseModel, Field

# ``metric_type``, ``operator``, ``severity`` and ``channel`` are plain strings
# on purpose: the service is the single validator and reports unsupported
# values as HTTP 400, whereas ``Literal`` fields would surface as a 422.


class AlertRuleCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    metric_type: str = Field(min_length=1, max_length=30)
    operator: str = Field(default=">", min_length=1, max_length=5)
    threshold: float
    window_minutes: int = Field(default=5, ge=1)
    consecutive_periods: int = Field(default=1, ge=1)
    severity: str = Field(default="P1", min_length=1, max_length=10)
    channel: str = Field(default="webhook", min_length=1, max_length=20)
    cooldown_minutes: int = Field(default=30, ge=0)
    enabled: bool = True


class AlertRuleUpdate(BaseModel):
    """Partial update; only fields present in the request are applied."""

    name: str | None = Field(default=None, min_length=1, max_length=100)
    metric_type: str | None = Field(default=None, min_length=1, max_length=30)
    operator: str | None = Field(default=None, min_length=1, max_length=5)
    threshold: float | None = None
    window_minutes: int | None = Field(default=None, ge=1)
    consecutive_periods: int | None = Field(default=None, ge=1)
    severity: str | None = Field(default=None, min_length=1, max_length=10)
    channel: str | None = Field(default=None, min_length=1, max_length=20)
    cooldown_minutes: int | None = Field(default=None, ge=0)
    enabled: bool | None = None
