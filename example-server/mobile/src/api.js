// 예제서버 API 호출. 휴대폰은 같은 와이파이의 컴퓨터(예제서버)에 http로 붙는다.
// 로그인은 세션 쿠키 방식이고, 쿠키는 휴대폰의 네트워크 계층이 알아서 보관 · 전송한다.
import AsyncStorage from '@react-native-async-storage/async-storage';
import Constants from 'expo-constants';
import { enrich } from './lib/catalog';

const KEY = 'fairticket.serverUrl';
let base = '';

/** Expo Go가 접속한 개발 컴퓨터의 IP로 예제서버 주소를 짐작한다 (같은 컴퓨터에서 서버를 띄웠을 때 맞는다) */
export function guessServerUrl() {
  const hostUri = Constants.expoConfig?.hostUri || Constants.expoGoConfig?.debuggerHost || '';
  const host = hostUri.split(':')[0];
  return /^\d+\.\d+\.\d+\.\d+$/.test(host) ? `http://${host}:8080` : 'http://192.168.0.2:8080';
}

export async function loadServerUrl() {
  let saved = null;
  try { saved = await AsyncStorage.getItem(KEY); } catch { saved = null; }
  base = saved || guessServerUrl();
  return base;
}

export async function saveServerUrl(url) {
  base = url.trim().replace(/\/+$/, '');
  if (!/^https?:\/\//.test(base)) base = `http://${base}`;
  try { await AsyncStorage.setItem(KEY, base); } catch { /* 저장 못 해도 이번 실행에는 쓴다 */ }
  return base;
}

export const serverUrl = () => base;

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

export async function api(method, path, body) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  let res;
  try {
    res = await fetch(base + path, {
      method,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'include',
      signal: controller.signal,
    });
  } catch {
    throw new ApiError(`서버에 연결할 수 없습니다 (${base}). 같은 와이파이인지, 서버가 켜져 있는지 확인하세요.`, 0);
  } finally {
    clearTimeout(timer);
  }
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
export const del = (path) => api('DELETE', path);

/** 공연 목록 + 상세(좌석 수 · 등급) + 화면용 정보(장르 · 아티스트 · 포스터 색) */
export async function fetchConcerts(keyword = '') {
  const list = await get(`/api/concerts?keyword=${encodeURIComponent(keyword)}`);
  const details = await Promise.all(list.map((c) => get(`/api/concerts/${c.id}`).catch(() => c)));
  return details.map(enrich);
}

export async function fetchConcert(id) {
  return enrich(await get(`/api/concerts/${id}`));
}
