import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth.jsx';
import { get } from '../api.js';
import { GENRES } from '../catalog.js';
import Icon from './Icon.jsx';

export default function Header() {
  const { me, logout, toast } = useAuth();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const [params] = useSearchParams();
  const [keyword, setKeyword] = useState(params.get('q') || '');
  const [offerCount, setOfferCount] = useState(0);

  // 나에게 온 재배정 제안이 있으면 종 아이콘에 숫자로 표시
  useEffect(() => {
    if (!me) { setOfferCount(0); return undefined; }
    let alive = true;
    const load = () => get('/api/reallocation/offers').then((o) => alive && setOfferCount(o.length)).catch(() => {});
    load();
    const id = setInterval(load, 10000);
    return () => { alive = false; clearInterval(id); };
  }, [me]);

  useEffect(() => { setKeyword(params.get('q') || ''); }, [params]);

  const search = (e) => {
    e.preventDefault();
    navigate(keyword.trim() ? `/concerts?q=${encodeURIComponent(keyword.trim())}` : '/concerts');
  };

  const onList = pathname === '/concerts';
  const genre = onList ? params.get('genre') || '' : null;
  const state = onList ? params.get('state') || '' : null;
  const sort = onList ? params.get('sort') || '' : null;
  const menu = (to, label, on) => <Link key={to} to={to} className={on ? 'active' : ''}>{label}</Link>;

  return (
    <header className="header">
      <div className="util">
        <div className="wrap util-row">
          <span className="util-note"><Icon name="shield" size={14} /> 모든 티켓은 FairChain 블록체인에 기록됩니다</span>
          <nav>
            {me ? (
              <>
                <span><b>{me.name}</b> 님</span>
                <button onClick={async () => { await logout(); toast('로그아웃했습니다'); navigate('/'); }}>로그아웃</button>
              </>
            ) : (
              <>
                <Link to="/login">로그인</Link>
                <Link to="/signup">회원가입</Link>
              </>
            )}
            <Link to="/admin" target="_blank" rel="noreferrer">운영자</Link>
            <Link to="/gate" target="_blank" rel="noreferrer">게이트</Link>
          </nav>
        </div>
      </div>

      <div className="wrap header-main">
        <Link to="/" className="logo">
          <span className="logo-mark">F</span>
          <span className="logo-text">Fair<b>Ticket</b></span>
        </Link>
        <form className="search" onSubmit={search}>
          <input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="공연명, 아티스트, 공연장을 검색해 보세요" />
          <button type="submit" aria-label="검색"><Icon name="search" /></button>
        </form>
        <div className="quick">
          <Link to="/tickets" className="quick-item"><Icon name="ticket" size={24} /><span>내 티켓</span></Link>
          <Link to="/reallocation" className="quick-item">
            <Icon name="bell" size={24} />{offerCount > 0 && <i className="quick-dot">{offerCount}</i>}
            <span>취소표 알림</span>
          </Link>
          <Link to={me ? '/tickets' : '/login'} className="quick-item"><Icon name="user" size={24} /><span>{me ? me.name : '로그인'}</span></Link>
        </div>
      </div>

      <nav className="gnb">
        <div className="wrap gnb-row">
          {menu('/', '홈', pathname === '/')}
          {menu('/concerts', '전체 공연', onList && !genre && !state && !sort)}
          {GENRES.map((g) => menu(`/concerts?genre=${g.key}`, g.label, genre === g.key))}
          <span className="gnb-gap" />
          {menu('/concerts?sort=rank', '랭킹', sort === 'rank')}
          {menu('/concerts?state=upcoming', '오픈예정', state === 'upcoming')}
        </div>
      </nav>
    </header>
  );
}
