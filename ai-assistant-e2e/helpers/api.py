"""HTTP 薄封装：统一基址 / Bearer 注入 / Result 解包（成功 code=200）"""

import random

import httpx

from helpers.sse import consume

SUCCESS_CODE = 200


class ApiError(Exception):
    """HTTP 非 2xx 或 Result.code != 200"""

    def __init__(self, method: str, url: str, status: int, body):
        self.status = status
        self.body = body
        super().__init__(f"{method} {url} -> HTTP {status}: {str(body)[:300]}")


def gen_phone() -> str:
    """生成符合 ^1\\d{10}$ 的随机手机号（碰撞概率可忽略：8 亿空间）"""
    return f"13{random.randint(0, 999_999_999):09d}"


def make_client(base_url: str, fast_timeout: float = 20, read_timeout: float = 120) -> httpx.Client:
    """统一客户端：连接/写入用快超时，读超时放宽以覆盖 LLM 流式输出"""
    return httpx.Client(base_url=base_url, timeout=httpx.Timeout(fast_timeout, read=read_timeout))


def unwrap(resp: httpx.Response) -> object:
    """校验并解包 Result：HTTP 2xx 且 code==200 时返回 data，否则抛 ApiError"""
    try:
        body = resp.json()
    except ValueError:
        raise ApiError(resp.request.method, str(resp.request.url), resp.status_code, resp.text) from None
    if resp.status_code >= 400 or not isinstance(body, dict) or body.get("code") != SUCCESS_CODE:
        raise ApiError(resp.request.method, str(resp.request.url), resp.status_code, body)
    return body.get("data")


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def login_staff(client: httpx.Client, username: str, password: str) -> str:
    """员工登录，返回 JWT"""
    resp = client.post("/api/login", json={"username": username, "password": password})
    return unwrap(resp)["token"]


def login_patient(client: httpx.Client, phone: str, password: str) -> str:
    """患者登录（username=手机号），返回 JWT"""
    resp = client.post("/api/patient/login", json={"username": phone, "password": password})
    return unwrap(resp)["token"]


def register_patient(client: httpx.Client, phone: str, password: str, name: str, hospital_id: int) -> int:
    """注册患者账号，返回新账号 ID"""
    resp = client.post(
        "/api/patient/register",
        json={"phone": phone, "password": password, "name": name, "hospitalId": hospital_id},
    )
    return unwrap(resp)


def get_info(client: httpx.Client, token: str) -> dict:
    """当前登录用户信息（含 userId）"""
    return unwrap(client.get("/api/getInfo", headers=_auth(token)))


def logout(client: httpx.Client, token: str) -> None:
    unwrap(client.post("/api/logout", headers=_auth(token)))


def kickout(client: httpx.Client, admin_token: str, user_id: int) -> None:
    unwrap(client.post(f"/api/system/user/{user_id}/kickout", headers=_auth(admin_token)))


def public_hospitals(client: httpx.Client) -> list[dict]:
    """公开医院列表（匿名）"""
    return unwrap(client.get("/api/hospital/public/list"))


def departments(client: httpx.Client, token: str, hospital_id: int) -> list[dict]:
    """医院科室列表（需 hospital:manage）"""
    return unwrap(client.get("/api/hospital/department/list", params={"hospitalId": hospital_id}, headers=_auth(token)))


def create_patient_session(client: httpx.Client, token: str, session_type: str) -> str:
    """新建患者会话（CHAT/TRIAGE/RAG），返回 sessionId"""
    data = unwrap(client.post("/api/chat/patient/session", params={"type": session_type}, headers=_auth(token)))
    return data["sessionId"]


def create_staff_session(client: httpx.Client, token: str, title: str = "E2E会话") -> str:
    """新建员工会话，返回 sessionId"""
    data = unwrap(client.post("/api/chat/session", params={"title": title}, headers=_auth(token)))
    return data["sessionId"]


def patient_sessions(client: httpx.Client, token: str) -> list[dict]:
    return unwrap(client.get("/api/chat/patient/sessions", headers=_auth(token)))


def staff_sessions(client: httpx.Client, token: str) -> list[dict]:
    return unwrap(client.get("/api/chat/sessions", headers=_auth(token)))


def admin_sessions(client: httpx.Client, token: str, session_type: str | None = None, page_size: int = 100) -> dict:
    """管理端患者会话分页（本院范围），sessionType 可空=全部"""
    params = {"sessionType": session_type, "pageNum": 1, "pageSize": page_size}
    return unwrap(client.get("/api/chat/admin/sessions", params=params, headers=_auth(token)))


def patient_history(client: httpx.Client, token: str, session_id: str) -> dict:
    return unwrap(client.get(f"/api/chat/patient/history/{session_id}", headers=_auth(token)))


def chat_patient(
    client: httpx.Client,
    token: str,
    session_id: str,
    message: str,
    until: set[str] | None = None,
) -> list[tuple[str, object]]:
    """患者流式对话：返回 SSE 事件列表（读至流结束或 until 命中）"""
    with client.stream(
        "POST",
        "/api/chat/patient/stream",
        json={"sessionId": session_id, "message": message},
        headers=_auth(token),
    ) as resp:
        resp.raise_for_status()
        return consume(resp, until=until)


def confirm_action(
    client: httpx.Client,
    token: str,
    action_id: str,
    until: set[str] | None = None,
) -> list[tuple[str, object]]:
    """确认待确认操作（SSE 续答）"""
    with client.stream(
        "POST", f"/api/chat/patient/action/{action_id}/confirm", headers=_auth(token)
    ) as resp:
        resp.raise_for_status()
        return consume(resp, until=until)


def cancel_action(client: httpx.Client, token: str, action_id: str) -> None:
    """取消待确认操作"""
    unwrap(client.post(f"/api/chat/patient/action/{action_id}/cancel", headers=_auth(token)))


def pools(client: httpx.Client, token: str, date: str) -> list[dict]:
    """号源池列表（需 registration:schedule:manage），date=yyyy-MM-dd"""
    return unwrap(client.get("/api/registration/source/pools", params={"date": date}, headers=_auth(token)))


def registration_records(client: httpx.Client, token: str, patient_phone: str) -> dict:
    """挂号记录分页（需 registration:register:list），按患者手机号过滤"""
    return unwrap(client.get(
        "/api/registration/records",
        params={"patientPhone": patient_phone, "pageNum": 1, "pageSize": 50},
        headers=_auth(token),
    ))


def public_schedules(client: httpx.Client, date: str | None = None) -> list[dict]:
    """公开排班（匿名），date=yyyy-MM-dd 可空"""
    params = {"date": date} if date else {}
    return unwrap(client.get("/api/registration/schedule/public/schedules", params=params))


def upload_doc(client: httpx.Client, token: str, filename: str, content: bytes,
               content_type: str = "text/markdown") -> int:
    """上传知识库文档（md/txt ≤2MB），返回文档 ID"""
    resp = client.post(
        "/api/chat/admin/knowledge/upload",
        files={"file": (filename, content, content_type)},
        headers=_auth(token),
    )
    return unwrap(resp)


def list_docs(client: httpx.Client, token: str, page_size: int = 100) -> dict:
    return unwrap(client.get("/api/chat/admin/knowledge",
                             params={"pageNum": 1, "pageSize": page_size}, headers=_auth(token)))


def delete_doc(client: httpx.Client, token: str, doc_id: int) -> None:
    unwrap(client.delete(f"/api/chat/admin/knowledge/{doc_id}", headers=_auth(token)))
