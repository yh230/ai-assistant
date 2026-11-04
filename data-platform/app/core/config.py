from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        populate_by_name=True,
    )

    app_name: str = "data-platform"
    env: str = Field(default="dev", validation_alias=AliasChoices("APP_ENV", "ENV"))
    metadata_db_url: str = "sqlite:///./data-platform.db"
    source_db_url: str = ""
    jwt_secret: str = ""
    jwt_algorithm: str = "HS256"
    dp_secret_key: str = ""
    event_bus: str = "memory"
    olap_store: str = "memory"
    kafka_bootstrap: str = "127.0.0.1:9092"
    kafka_consumer_group: str = "dp-ods-loader"
    kafka_topic_prefix: str = "dp.ods"
    clickhouse_url: str = "http://127.0.0.1:8123"
    clickhouse_database: str = "data_platform"
    timezone: str = "Asia/Shanghai"
    # Generic webhook (DingTalk/WeCom-compatible text payload). Empty disables
    # delivery; the alert record is still persisted.
    alert_webhook_url: str = ""
    # Opt-in: importing app.scheduler never starts it, and no scheduler thread
    # is created unless the deployment sets DP_ENABLE_SCHEDULER=true.
    dp_enable_scheduler: bool = False
    # Screen (dashboard) query windows.  The ClickHouse repository clamps every
    # value to its own hard maximum, so a large request parameter can never
    # trigger an unbounded scan.
    screen_trend_days: int = 30
    screen_max_trend_days: int = 365
    screen_ranking_limit: int = 10
    screen_max_ranking_limit: int = 100
    screen_recent_alert_limit: int = 10
    screen_stream_interval_seconds: float = 5.0


def get_settings() -> Settings:
    return Settings()
