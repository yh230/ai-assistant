"""helpers/api.py 纯逻辑单测（不依赖服务）"""

import re

import httpx
import pytest

from helpers import api


def _resp(status=200, body=None, method="GET", url="http://t/api/x"):
    return httpx.Response(status, json=body, request=httpx.Request(method, url))


def test_unwrap_returns_data_on_success():
    r = _resp(body={"code": 200, "msg": "ok", "data": {"a": 1}})
    assert api.unwrap(r) == {"a": 1}


def test_unwrap_raises_on_business_error():
    r = _resp(body={"code": 500, "msg": "仅支持 md/txt 格式文档", "data": None})
    with pytest.raises(api.ApiError) as ei:
        api.unwrap(r)
    assert "md/txt" in str(ei.value)


def test_unwrap_raises_on_http_error():
    r = _resp(status=403, body={"code": 403, "msg": "权限不足"})
    with pytest.raises(api.ApiError) as ei:
        api.unwrap(r)
    assert ei.value.status == 403


def test_gen_phone_matches_pattern_and_unique():
    phones = {api.gen_phone() for _ in range(20)}
    assert len(phones) == 20
    assert all(re.fullmatch(r"1\d{10}", p) for p in phones)
