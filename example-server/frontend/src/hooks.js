import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { fetchConcerts } from './api.js';
import { useAuth } from './auth.jsx';

/** 로그인이 필요한 화면 — 비로그인이면 로그인 뒤 돌아올 주소(next)를 붙여 보낸다. 로그인 상태를 돌려준다 */
export function useRequireLogin(next) {
  const { me } = useAuth();
  const navigate = useNavigate();
  useEffect(() => {
    if (!me) navigate(`/login?next=${encodeURIComponent(next)}`);
  }, [me, navigate, next]);
  return !!me;
}

/**
 * load를 바로 한 번 부르고, 그 뒤 intervalMs마다 다시 부른다 (화면이 FairChain 기록 변화를 따라가게).
 * enabled가 false면 아무것도 하지 않는다. load는 useCallback으로 고정해서 넘길 것.
 */
export function usePolling(load, intervalMs, enabled = true) {
  useEffect(() => {
    if (!enabled) return undefined;
    load();
    const t = setInterval(load, intervalMs);
    return () => clearInterval(t);
  }, [load, intervalMs, enabled]);
}

// 화면을 옮겨 다닐 때 목록이 비었다가 채워지지 않도록 마지막 결과를 기억해 둔다 (검색어별)
const concertCache = new Map();

/**
 * 공연 목록 (화면용 정보 포함). 기억해 둔 결과를 먼저 보여 주고 새로 받아 온 것으로 바꾼다.
 * refreshMs를 주면 그 주기로 다시 받아 온다 (잔여 좌석 · 예매율 갱신).
 */
export function useConcerts(keyword = '', refreshMs = 0) {
  const [concerts, setConcerts] = useState(() => concertCache.get(keyword) || []);
  const [loading, setLoading] = useState(!concertCache.has(keyword));
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const list = await fetchConcerts(keyword);
      concertCache.set(keyword, list);
      setConcerts(list);
      setError('');
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [keyword]);

  useEffect(() => {
    setConcerts(concertCache.get(keyword) || []);
    setLoading(!concertCache.has(keyword));
    load();
    if (!refreshMs) return undefined;
    const t = setInterval(load, refreshMs);
    return () => clearInterval(t);
  }, [keyword, load, refreshMs]);

  return { concerts, loading, error, reload: load };
}

/** 1초마다 바뀌는 현재 시각 (카운트다운용) */
export function useNow(intervalMs = 1000) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(t);
  }, [intervalMs]);
  return now;
}
