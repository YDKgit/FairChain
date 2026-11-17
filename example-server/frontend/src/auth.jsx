import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { get, post } from './api.js';

const AuthContext = createContext(null);

const defaultConfig = {
  queueEnabled: true,
  fairChainUrl: '',
  reallocation: { offerTime: '20:00', releaseTime: '09:00', offerTtlMinutes: 10, finalWindowHours: 24 },
};

/** 로그인한 회원 · 서버 설정 · 토스트를 화면 전체에서 쓴다 */
export function AuthProvider({ children }) {
  const [me, setMe] = useState(null);
  const [ready, setReady] = useState(false);
  const [config, setConfig] = useState(defaultConfig);
  const [toastMsg, setToastMsg] = useState(null);

  const refresh = useCallback(async () => {
    try { setMe(await get('/api/members/me')); } catch { setMe(null); }
  }, []);

  useEffect(() => {
    (async () => {
      try { setConfig({ ...defaultConfig, ...(await get('/api/config')) }); } catch { /* 기본값 */ }
      await refresh();
      setReady(true);
    })();
  }, [refresh]);

  const login = useCallback(async (loginId, password) => {
    const member = await post('/api/members/login', { loginId, password });
    setMe(member);
    return member;
  }, []);

  const logout = useCallback(async () => {
    await post('/api/members/logout');
    setMe(null);
  }, []);

  const toast = useCallback((message, kind = 'info') => {
    setToastMsg({ message, kind, id: Date.now() });
  }, []);

  useEffect(() => {
    if (!toastMsg) return undefined;
    const t = setTimeout(() => setToastMsg(null), 3200);
    return () => clearTimeout(t);
  }, [toastMsg]);

  const value = useMemo(() => ({ me, ready, config, refresh, login, logout, toast, toastMsg }),
    [me, ready, config, refresh, login, logout, toast, toastMsg]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
