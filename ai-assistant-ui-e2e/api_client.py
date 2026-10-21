import random
from dataclasses import dataclass

import httpx


@dataclass
class Session:
    token: str
    refresh_token: str
    user: dict


def gen_phone() -> str:
    return f"13{random.randint(0, 999_999_999):09d}"


class ApiClient:
    def __init__(self, base_url: str, timeout: float = 20):
        self.client = httpx.Client(
            base_url=base_url.rstrip("/"),
            timeout=httpx.Timeout(timeout, read=180),
        )

    def close(self) -> None:
        self.client.close()

    @staticmethod
    def _auth(token: str) -> dict:
        return {"Authorization": f"Bearer {token}"}

    def _unwrap(self, response: httpx.Response):
        try:
            body = response.json()
        except ValueError:
            raise AssertionError(
                f"{response.request.method} {response.request.url} -> "
                f"{response.status_code}: {response.text[:300]}"
            ) from None
        if response.status_code >= 400 or not isinstance(body, dict) or body.get("code") != 200:
            raise AssertionError(
                f"{response.request.method} {response.request.url} -> "
                f"{response.status_code}: {body}"
            )
        return body.get("data")

    def login_staff(self, username: str, password: str) -> Session:
        data = self._unwrap(self.client.post("/api/login", json={
            "username": username,
            "password": password,
        }))
        token = data["token"]
        refresh = data.get("refreshToken", "")
        user = self._unwrap(self.client.get("/api/getInfo", headers=self._auth(token)))
        return Session(token=token, refresh_token=refresh, user=user)

    def login_patient(self, phone: str, password: str) -> Session:
        data = self._unwrap(self.client.post("/api/patient/login", json={
            "username": phone,
            "password": password,
        }))
        token = data["token"]
        refresh = data.get("refreshToken", "")
        # 患者令牌不能访问员工路径 /api/getInfo；前端本身也只缓存 phone
        return Session(token=token, refresh_token=refresh, user={"phone": phone})

    def register_patient(self, phone: str, password: str, name: str, hospital_id: int):
        return self._unwrap(self.client.post("/api/patient/register", json={
            "phone": phone,
            "password": password,
            "name": name,
            "hospitalId": hospital_id,
        }))

    def public_hospitals(self) -> list[dict]:
        return self._unwrap(self.client.get("/api/hospital/public/list"))

    def hospital_id(self, name: str) -> int:
        for hospital in self.public_hospitals():
            if hospital["name"] == name:
                return hospital["id"]
        raise AssertionError(f"公开医院列表缺少「{name}」")

    def departments(self, token: str, hospital_id: int) -> list[dict]:
        return self._unwrap(self.client.get(
            "/api/hospital/department/list",
            params={"hospitalId": hospital_id},
            headers=self._auth(token),
        ))

    def list_docs(self, token: str, page_size: int = 100) -> dict:
        return self._unwrap(self.client.get(
            "/api/chat/admin/knowledge",
            params={"pageNum": 1, "pageSize": page_size},
            headers=self._auth(token),
        ))

    def upload_doc(self, token: str, filename: str, content: bytes):
        resp = self.client.post(
            "/api/chat/admin/knowledge/upload",
            files={"file": (filename, content, "text/markdown")},
            headers=self._auth(token),
        )
        return self._unwrap(resp)

    def delete_doc(self, token: str, doc_id: int) -> None:
        self._unwrap(self.client.delete(
            f"/api/chat/admin/knowledge/{doc_id}",
            headers=self._auth(token),
        ))

    def delete_doc_by_name(self, token: str, filename: str) -> None:
        docs = self.list_docs(token)
        for doc in docs.get("list", []):
            if doc.get("name") == filename:
                try:
                    self.delete_doc(token, doc["id"])
                except Exception:
                    pass
                return

    def registration_records(self, token: str, patient_phone: str) -> dict:
        return self._unwrap(self.client.get(
            "/api/registration/records",
            params={"patientPhone": patient_phone, "pageNum": 1, "pageSize": 50},
            headers=self._auth(token),
        ))

    def public_schedules(self, date: str | None = None) -> list[dict]:
        params = {"date": date} if date else {}
        return self._unwrap(self.client.get(
            "/api/registration/schedule/public/schedules",
            params=params,
        ))
