import axios from 'axios';

import { requestMock } from '../mock/governance.js';
import { overview, quality, ranking, trend } from '../mock/screen.js';

const baseURL = import.meta.env.VITE_API_BASE_URL || '/api';
const useMock = import.meta.env.VITE_USE_MOCK === 'true';

const http = axios.create({ baseURL, timeout: 30000 });

http.interceptors.request.use((config) => {
  try {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
  } catch {
    // localStorage is unavailable in some privacy modes; keep the call anonymous.
  }
  return config;
});

function unwrap(response) {
  const body = response?.data;
  if (body && typeof body === 'object' && 'code' in body) {
    if (body.code !== 0) {
      throw new Error(body.message || `接口返回错误码 ${body.code}`);
    }
    return body.data;
  }
  return body;
}

function messageOf(error) {
  const body = error?.response?.data;
  if (body && typeof body === 'object' && body.message) {
    return String(body.message);
  }
  return error?.message || '请求失败';
}

async function request(method, path, body) {
  if (useMock) {
    return requestMock(method, path, body);
  }
  try {
    const config = { method, url: path, data: body };
    // Manual sync / quality runs execute synchronously on the backend, so they
    // must not be cut off by the default UI timeout.
    if (/\/run$/.test(path.split('?')[0])) {
      config.timeout = 0;
    }
    const response = await http.request(config);
    return unwrap(response);
  } catch (error) {
    throw new Error(messageOf(error));
  }
}

export const apiBaseUrl = baseURL.replace(/\/$/, '');
export const isMockMode = useMock;

/** Same-origin URL for the SSE stream consumed by EventSource. */
export function screenStreamUrl(path = '/screen/stream') {
  return `${apiBaseUrl}${path}`;
}

export function getJson(path) {
  return request('get', path);
}

export function postJson(path, body) {
  return request('post', path, body);
}

export function deleteJson(path) {
  return request('delete', path);
}

export async function fetchOverview() {
  if (useMock) return overview;
  return getJson('/screen/overview');
}

export async function fetchTrend(days) {
  if (useMock) return trend;
  return getJson(days ? `/screen/trend?days=${days}` : '/screen/trend');
}

export async function fetchRanking(limit) {
  if (useMock) return ranking;
  return getJson(limit ? `/screen/ranking?limit=${limit}` : '/screen/ranking');
}

export async function fetchQuality() {
  if (useMock) return quality;
  return getJson('/screen/quality');
}
