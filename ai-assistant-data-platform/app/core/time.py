"""The single platform clock (Asia/Shanghai wall-clock, naive).

Metadata tables store timestamps without a time zone and the platform
convention is Asia/Shanghai (design section 9).  Every default and service
timestamp uses this helper instead of ``datetime.utcnow`` so a record written
at the same instant by the API, the scheduler or the ETL carries one value.
"""

from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

PLATFORM_TIMEZONE = "Asia/Shanghai"

try:
    PLATFORM_TZ: timezone | ZoneInfo = ZoneInfo(PLATFORM_TIMEZONE)
except ZoneInfoNotFoundError:  # Windows without the tzdata package
    PLATFORM_TZ = timezone(timedelta(hours=8), PLATFORM_TIMEZONE)


def now_shanghai() -> datetime:
    """Current Asia/Shanghai wall-clock time with the tzinfo dropped."""
    return datetime.now(PLATFORM_TZ).replace(tzinfo=None)
