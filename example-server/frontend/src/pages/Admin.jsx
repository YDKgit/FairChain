import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { fetchConcerts, get, post } from '../api.js';
import { useAuth } from '../auth.jsx';
import LoginForm from '../components/LoginForm.jsx';
import Dashboard from './admin/Dashboard.jsx';
import ReallocPanel from './admin/ReallocPanel.jsx';
import ConcertPanel from './admin/ConcertPanel.jsx';
import SeatPanel from './admin/SeatPanel.jsx';
import StaffPanel from './admin/StaffPanel.jsx';

const TABS = [['dashboard', '대시보드'], ['realloc', '취소표 재배정'], ['concerts', '공연 관리'], ['seats', '좌석 등록'], ['staff', '게이트 스태프']];

/** 운영자 화면 — 시연 때 사용자 화면과 나란히 띄운다 (/admin). 탭마다 admin/ 아래 패널 하나 */
export default function Admin() {
  const { toast, config } = useAuth();
  const [operator, setOperator] = useState(undefined);   // undefined: 확인 중, null: 로그인 필요
  const [keys, setKeys] = useState(null);
  const [tab, setTab] = useState('dashboard');
  const [concerts, setConcerts] = useState([]);

  const loadConcerts = useCallback(() => fetchConcerts().then(setConcerts).catch(() => {}), []);

  /** 세션 확인 → 공연 목록 · FairChain 키 (키 조회는 FairChain이 꺼져 있으면 실패해도 화면은 연다) */
  const check = useCallback(async () => {
    try { setOperator(await get('/api/operator/me')); } catch { setOperator(null); return; }
    await loadConcerts();
    try { setKeys(await get('/api/operator/keys')); } catch (e) { setKeys({ error: e.message }); }
  }, [loadConcerts]);

  useEffect(() => { check(); }, [check]);

  if (operator === undefined) return <div className="boot">확인 중…</div>;
  if (operator === null) {
    return (
      <LoginForm title="운영자 로그인" path="/api/operator/login" defaultId="operator" onDone={check}
        hint="비밀번호는 실행 스크립트가 만든 data/local-secrets.json 에 있습니다." />
    );
  }

  return (
    <div className="admin">
      <header className="admin-top">
        <div className="logo"><span className="logo-mark dark">F</span><span className="logo-text">Fair<b>Ticket</b> <small>운영자</small></span></div>
        <nav>{TABS.map(([k, label]) => <button key={k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{label}</button>)}</nav>
        <div className="row gap">
          <span className="muted small">{operator.name}</span>
          <Link to="/" target="_blank" className="link muted">사용자 화면 ↗</Link>
          <button className="link" onClick={async () => { await post('/api/operator/logout'); setOperator(null); }}>로그아웃</button>
        </div>
      </header>
      <div className="admin-body">
        {tab === 'dashboard' && <Dashboard concerts={concerts} keys={keys} fairChainUrl={config.fairChainUrl} />}
        {tab === 'realloc' && <ReallocPanel concerts={concerts} toast={toast} />}
        {tab === 'concerts' && <ConcertPanel concerts={concerts} reload={loadConcerts} toast={toast} />}
        {tab === 'seats' && <SeatPanel concerts={concerts} reload={loadConcerts} toast={toast} />}
        {tab === 'staff' && <StaffPanel toast={toast} />}
      </div>
    </div>
  );
}
