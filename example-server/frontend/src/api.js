// 예제서버 API 호출 — 모든 오류는 {message} 형태라 그대로 보여 준다
import { enrich } from './catalog.js';

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

export async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: 'same-origin',
  });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!res.ok) {
    throw new ApiError((data && data.message) || `요청 실패 (HTTP ${res.status})`, res.status);
  }
  return data;
}

export const get = (path) => api('GET', path);
export const post = (path, body) => api('POST', path, body ?? {});
export const put = (path, body) => api('PUT', path, body ?? {});
export const del = (path) => api('DELETE', path);

/**
 * 공연 목록 + 상세(좌석 수 · 등급) + 화면용 정보(장르 · 아티스트 · 포스터 색).
 * 목록 API는 좌석 현황을 주지 않아 상세를 함께 가져온다. 상세를 못 가져온 공연은 목록 항목 그대로 둔다.
 */
export async function fetchConcerts(keyword = '') {
  const list = await get(`/api/concerts?keyword=${encodeURIComponent(keyword)}`);
  const details = await Promise.all(list.map((c) => get(`/api/concerts/${c.id}`).catch(() => c)));
  return details.map(enrich);
}

/** 공연 한 건 + 화면용 정보 */
export async function fetchConcert(id) {
  return enrich(await get(`/api/concerts/${id}`));
}
