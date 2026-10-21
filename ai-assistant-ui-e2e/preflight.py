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
    staff1 = None
    hospitals: dict[str, int] = {}
    try:
        check("前端", lambda: http_ok(settings.base_url))
        check("网关健康检查", lambda: http_ok(f"{settings.api_base}/api/health"))

        def login_admin():
            nonlocal admin
            admin = api.login_staff(settings.admin_user, settings.admin_password)
            return admin.user.get("userId", "OK")

        check("admin 登录", login_admin)

        def login_staff1():
            nonlocal staff1
            if not settings.staff1_user or not settings.staff1_password:
                raise RuntimeError(
                    "未配置 UI_E2E_STAFF1_USER / UI_E2E_STAFF1_PASSWORD（知识库用例必需）"
                )
            staff1 = api.login_staff(settings.staff1_user, settings.staff1_password)
            return staff1.user.get("userId", "OK")

        check("staff1 登录", login_staff1)

        def demo_hospitals():
            if not settings.hospital1 or not settings.hospital2:
                raise RuntimeError(
                    "未配置 UI_E2E_HOSPITAL1_NAME / UI_E2E_HOSPITAL2_NAME"
                )
            hospitals.update({h["name"]: h["id"] for h in api.public_hospitals()})
            missing = [
                name for name in (settings.hospital1, settings.hospital2)
                if name not in hospitals
            ]
            if missing:
                raise RuntimeError(f"公开医院列表缺少：{'、'.join(missing)}")
            return (
                f"{settings.hospital1}={hospitals[settings.hospital1]}, "
                f"{settings.hospital2}={hospitals[settings.hospital2]}"
            )

        check("两家演示医院", demo_hospitals)

        def hospital1_departments():
            if admin is None:
                raise RuntimeError("admin 登录失败，无法查询医院 1 科室")
            hid = hospitals.get(settings.hospital1)
            if hid is None:
                raise RuntimeError("医院 1 未解析，无法查询科室")
            return f"{len(api.departments(admin.token, hid))} 个"

        check("医院 1 科室", hospital1_departments)

        def staff1_knowledge():
            if staff1 is None:
                raise RuntimeError("staff1 登录失败，无法查询知识库")
            docs = api.list_docs(staff1.token)
            return f"{docs.get('total', 0)} 条"

        check("staff1 知识库列表", staff1_knowledge)

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
