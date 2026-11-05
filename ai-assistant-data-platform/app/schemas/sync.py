from pydantic import BaseModel, Field

# ``watermark_type`` is a plain string here on purpose: ``SyncService`` is the
# single validator (``datetime`` / ``numeric``) and reports an unsupported
# value as HTTP 400, whereas a ``Literal`` field would surface as a 422.


class SyncTaskCreate(BaseModel):
    datasource_id: int
    source_schema: str = Field(min_length=1, max_length=100)
    source_table: str = Field(min_length=1, max_length=100)
    target_table: str = Field(min_length=1, max_length=100)
    primary_key: str = Field(default="id", min_length=1, max_length=50)
    watermark_column: str | None = Field(default=None, max_length=50)
    watermark_type: str = Field(default="datetime", description="datetime or numeric")
    batch_size: int = Field(default=500, ge=1, le=10000)
    overlap_seconds: int = Field(default=60, ge=0, le=86400)
    cron: str = Field(default="*/10 * * * *", min_length=1, max_length=50)
    enabled: bool = True


class SyncTaskUpdate(BaseModel):
    """Partial update; only fields present in the request are applied."""

    datasource_id: int | None = None
    source_schema: str | None = Field(default=None, min_length=1, max_length=100)
    source_table: str | None = Field(default=None, min_length=1, max_length=100)
    target_table: str | None = Field(default=None, min_length=1, max_length=100)
    primary_key: str | None = Field(default=None, min_length=1, max_length=50)
    watermark_column: str | None = Field(default=None, max_length=50)
    watermark_type: str | None = Field(default=None, description="datetime or numeric")
    batch_size: int | None = Field(default=None, ge=1, le=10000)
    overlap_seconds: int | None = Field(default=None, ge=0, le=86400)
    cron: str | None = Field(default=None, min_length=1, max_length=50)
    enabled: bool | None = None
