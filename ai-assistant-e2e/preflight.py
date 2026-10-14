"""跑前自检：探测环境与前置数据，任一项失败即退出非 0。

用法：.venv\\Scripts\\python.exe preflight.py（或经 scripts/run-e2e.bat 自动调用）
"""

import os
import sys
from datetime import date, timedelta

from dotenv import load_dotenv

from helpers import api


def main() -> int:
    load_dotenv()
    base_url = os.environ.get("E2E_BASE_URL", "http://localhost:8080")
    fast = float(os.environ.get("E2E_FAST_TIMEOUT", "20"))
    read = float(os.environ.get("E2E_LLM_TIMEOUT", "120"))
    h1 = os.environ.get("E2E_HOSPITAL1_NAME", "")
    h2 = os.environ.get("E2E_HOSPITAL2_NAME", "")

    results: list[tuple[str, bool, str]] = []

    def step(name: str, fn) -> None:
        try:
            results.append((name, True, fn() or "OK"))
        except Exception as ex:  # 自检以可读输出为先，保留类型名便于定位
            results.append((name, False, f"{type(ex).__name__}: {str(ex)[:180]}"))

    with api.make_client(base_url, fast, read) as client:
        tokens: dict[str, str] = {}

        def check_gateway() -> str:
            resp = client.get("/api/health")
            if resp.status_code != 200:
                raise RuntimeError(f"HTTP {resp.status_code}（网关或服务未启动？先执行 scripts/start-services.bat）")
            return base_url

        def check_hospitals() -> str:
            if not h1 or not h2:
                raise RuntimeError("请在 .env 填写 E2E_HOSPITAL1_NAME / E2E_HOSPITAL2_NAME")
            names = {x["name"]: x["id"] for x in api.public_hospitals(client)}
            missing = [n for n in (h1, h2) if n not in names]
            if missing:
                raise RuntimeError(f"公开医院列表缺少 {missing}；现有：{list(names)}")
            return f"{h1} / {h2}"

        def check_logins() -> str:
            for key, user_env, pwd_env in (
                ("admin", "E2E_ADMIN_USER", "E2E_ADMIN_PASSWORD"),
                ("staff1", "E2E_STAFF1_USER", "E2E_STAFF1_PASSWORD"),
                ("staff2", "E2E_STAFF2_USER", "E2E_STAFF2_PASSWORD"),
            ):
                user = os.environ.get(user_env, "")
                pwd = os.environ.get(pwd_env, "")
                if not user or not pwd:
                    raise RuntimeError(f"请在 .env 填写 {user_env} / {pwd_env}")
                tokens[key] = api.login_staff(client, user, pwd)
                api.get_info(client, tokens[key])
            return "admin / staff1 / staff2 均可登录"

        def check_knowledge() -> str:
            page = api.list_docs(client, tokens["staff1"])
            return f"staff1 知识库可见 {page['total']} 篇"

        def check_departments() -> str:
            hid = {x["name"]: x["id"] for x in api.public_hospitals(client)}[h1]
            names = [d["name"] for d in api.departments(client, tokens["staff1"], hid)]
            if not names:
                raise RuntimeError(f"{h1} 无科室数据（检查 hospital_db 种子）")
            return f"{h1} 科室 {len(names)} 个"

        def check_registration_perms() -> str:
            tomorrow = (date.today() + timedelta(days=1)).isoformat()
            rows = api.pools(client, tokens["staff1"], tomorrow)
            api.registration_records(client, tokens["staff1"], "13900000000")
            return f"staff1 号源池可用（{len(rows)} 条）、挂号记录可分页查询"

        def check_schedules() -> str:
            tomorrow = (date.today() + timedelta(days=1)).isoformat()
            rows = api.public_schedules(client, tomorrow)
            bookable = [s for s in rows if (s.get("remaining") or 0) > 0]
            if not bookable:
                raise RuntimeError(f"{tomorrow} 无可约排班（请先生成排班与号源）")
            return f"{tomorrow} 可约排班 {len(bookable)} 条"

        step("网关 /api/health", check_gateway)
        step("公开医院列表", check_hospitals)
        step("账号登录（admin/staff1/staff2）", check_logins)
        step("知识库访问（staff1）", check_knowledge)
        step("科室列表（staff1 需 hospital:manage）", check_departments)
        step("挂号数据权限（staff1 需 registration:schedule:manage / registration:register:list）", check_registration_perms)
        step("明日可约号源", check_schedules)

    for name, ok, detail in results:
        print(f"[{'OK  ' if ok else 'FAIL'}] {name}：{detail}")
    failed = [r for r in results if not r[1]]
    if failed:
        print(f"\n[E2E] 前置自检未通过（{len(failed)}/{len(results)} 项失败），请先补齐上述事项。")
        return 1
    print(f"\n[E2E] 前置自检全部通过（{len(results)} 项）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
