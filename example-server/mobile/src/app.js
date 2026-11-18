// 앱 전체가 함께 쓰는 상태: 화면 이동(스택), 로그인한 회원, 서버 설정, 알림 띠
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { AppState, Vibration } from 'react-native';
import { get, post } from './api';
import { notifyOffer, onNotificationTap, setupNotifications } from './notify';

const AppContext = createContext(null);
export const useApp = () => useContext(AppContext);

export const TABS = ['home', 'tickets', 'realloc', 'me'];

export function AppProvider({ children }) {
  const [tab, setTab] = useState('home');
  const [stack, setStack] = useState([]);                 // 탭 위에 쌓이는 화면들 [{ name, params }]
  const [member, setMember] = useState(null);
  const [config, setConfig] = useState({ queueEnabled: true, reallocation: {} });
  const [toastMsg, setToastMsg] = useState(null);
  const toastTimer = useRef(null);

  const toast = useCallback((message, kind = 'ok') => {
    clearTimeout(toastTimer.current);
    setToastMsg({ message, kind });
    toastTimer.current = setTimeout(() => setToastMsg(null), 2600);
  }, []);

  const nav = useMemo(() => ({
    push: (name, params = {}) => setStack((s) => [...s, { name, params }]),
    replace: (name, params = {}) => setStack((s) => [...s.slice(0, -1), { name, params }]),
    back: () => setStack((s) => s.slice(0, -1)),
    home: (nextTab) => { setStack([]); if (nextTab) setTab(nextTab); },
  }), []);

  /** 서버에 다시 물어 로그인 상태 · 설정을 맞춘다 (앱을 켤 때, 서버 주소를 바꿨을 때) */
  const refresh = useCallback(async () => {
    try { setConfig(await get('/api/config')); } catch { /* 서버에 못 붙으면 기본값 */ }
    try { setMember(await get('/api/members/me')); } catch { setMember(null); }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const login = useCallback(async (loginId, password) => {
    const m = await post('/api/members/login', { loginId, password });
    setMember(m);
    return m;
  }, []);

  const logout = useCallback(async () => {
    try { await post('/api/members/logout'); } catch { /* 서버가 꺼져 있어도 앱에서는 로그아웃 */ }
    setMember(null);
  }, []);

  /** 로그인이 필요한 화면으로 — 로그인돼 있지 않으면 로그인 화면을 거쳐 간다 */
  const requireLogin = useCallback((name, params = {}) => {
    if (member) nav.push(name, params);
    else nav.push('login', { then: { name, params } });
  }, [member, nav]);

  // ── 취소표 알림 ── 로그인해 있는 동안 어느 화면에서든 "나에게 온 제안"을 5초마다 묻고,
  // 처음 보는 제안이면 휴대폰 알림 + 앱 위쪽 배너를 띄운다
  const [offers, setOffers] = useState([]);
  const [hidden, setHidden] = useState([]);               // 사용자가 닫은 배너의 제안 번호
  const seen = useRef(new Set());
  const memberId = member?.id ?? null;

  const checkOffers = useCallback(async () => {
    if (!memberId) return;
    let list;
    try { list = await get('/api/reallocation/offers'); } catch { return; }
    setOffers(list);
    const fresh = list.filter((o) => !seen.current.has(o.offerId));
    if (!fresh.length) return;
    fresh.forEach((o) => { seen.current.add(o.offerId); notifyOffer(o); });
    Vibration.vibrate([0, 250, 150, 250]);
  }, [memberId]);

  useEffect(() => {
    seen.current = new Set(); setOffers([]); setHidden([]);
    if (!memberId) return undefined;
    setupNotifications();
    checkOffers();
    const timer = setInterval(checkOffers, 5000);
    // 다른 앱을 보다가 돌아오면 기다리지 않고 바로 확인한다
    const sub = AppState.addEventListener('change', (state) => { if (state === 'active') checkOffers(); });
    return () => { clearInterval(timer); sub.remove(); };
  }, [memberId, checkOffers]);

  // 휴대폰 알림을 누르면 바로 그 취소표를 고르는 화면으로
  useEffect(() => onNotificationTap((data) => {
    setStack([{ name: 'seats', params: { id: data.concertId, offerToken: data.offerToken } }]);
  }), []);

  const banner = offers.find((o) => !hidden.includes(o.offerId)) || null;
  const dismissBanner = useCallback((offerId) => setHidden((h) => [...h, offerId]), []);

  const value = {
    tab, setTab, stack, nav, member, config, login, logout, refresh, requireLogin, toast, toastMsg,
    offers, banner, dismissBanner, checkOffers,
  };
  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

/** 화면이 떠 있는 동안 interval마다 fn을 부른다 (처음 한 번은 바로) */
export function usePolling(fn, interval, enabled = true) {
  const saved = useRef(fn);
  saved.current = fn;
  useEffect(() => {
    if (!enabled) return undefined;
    saved.current();
    const timer = setInterval(() => saved.current(), interval);
    return () => clearInterval(timer);
  }, [interval, enabled]);
}

/** 1초마다 바뀌는 현재 시각 (남은 시간 표시용) */
export function useNow() {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  return now;
}
