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
    # Opt-in: importing app.scheduler never starts it, and no scheduler thread
    # is created unless the deployment sets DP_ENABLE_SCHEDULER=true.
    dp_enable_scheduler: bool = False


def get_settings() -> Settings:
    return Settings()
