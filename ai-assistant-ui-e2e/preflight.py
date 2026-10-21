import sys
from datetime import date, timedelta

import httpx

from api_client import ApiClient
from config import settings


def main() -> int:
    results: list[tuple[str, bool, str]] = []

    def check(name: str, fn) -> None:
        try:
            results.append((name, True, str(fn())))
        except Exception as ex:
            results.append((name, False, f"{type(ex).__name__}: {ex}"))

    def http_ok(url: str) -> str:
        resp = httpx.get(url, timeout=10)
        if resp.status_code != 200:
            raise RuntimeError(f"HTTP {resp.status_code}")
        return url

    api = ApiClient(settings.api_base)
    admin = None
    hospitals: dict[str, int] = {}
    try:
        check("前端", lambda: http_ok(settings.base_url))
        check("网关健康检查", lambda: http_ok(f"{settings.api_base}/api/health"))

        def login_admin():
            nonlocal admin
            admin = api.login_staff(settings.admin_user, settings.admin_password)
            return admin.user.get("userId", "OK")

        check("admin 登录", login_admin)

        def demo_hospitals():
            hospitals.update({h["name"]: h["id"] for h in api.public_hospitals()})
            return hospitals[settings.hospital1], hospitals[settings.hospital2]

        check("两家演示医院", demo_hospitals)

        hid = hospitals.get(settings.hospital1)
        if admin is not None and hid is not None:
            check("医院 1 科室", lambda: f"{len(api.departments(admin.token, hid))} 个")

        tomorrow = (date.today() + timedelta(days=1)).isoformat()

        def bookable_schedules():
            schedules = api.public_schedules(tomorrow)
            bookable = [s for s in schedules if (s.get("remaining") or 0) > 0]
            if not bookable:
                raise RuntimeError(f"{tomorrow} 没有余量大于 0 的排班")
            return f"{len(bookable)} 条"

        check("明日可约排班", bookable_schedules)
    finally:
        api.close()

    for name, ok, detail in results:
        print(f"[{'OK  ' if ok else 'FAIL'}] {name}：{detail}")

    failed = [r for r in results if not r[1]]
    if failed:
        print(f"\n[UI E2E] 前置检查失败 {len(failed)}/{len(results)} 项，请先修复环境。")
        return 1
    print(f"\n[UI E2E] 前置检查通过 {len(results)}/{len(results)} 项。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
