import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { GENRES } from '../catalog.js';
import ConcertCard from '../components/ConcertCard.jsx';
import Icon from '../components/Icon.jsx';
import Poster from '../components/Poster.jsx';
import { SaleBadge } from '../components/Badge.jsx';
import { useConcerts } from '../hooks.js';
import { bookedRate, clock, dateParts, dday, daysUntil, saleState, when, won } from '../utils.js';

const TRUST = [
  ['queue', '공정한 대기열', '예매 오픈 순간 FairChain이 순번을 매기고, 매크로와 반복 요청을 걸러 냅니다.'],
  ['chain', '블록체인 기록', '좌석 발행 · 판매 · 취소 · 입장 때마다 티켓 해시값이 독립 노드에 남습니다.'],
  ['shield', '위조 티켓 차단', '입장 게이트가 QR을 블록체인 기록과 대조합니다. 기록과 다르면 입장이 거절됩니다.'],
  ['swap', '취소표 순번 재배정', '매진 뒤 취소표는 새로고침 경쟁 없이 대기 순번대로 한 사람씩 제안됩니다.'],
];

/** 맨 위 큰 배너 — 예매할 수 있는 공연을 차례로 보여 준다 */
function Hero({ concerts }) {
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const count = concerts.length;

  useEffect(() => {
    if (count < 2 || paused) return undefined;
    const id = setInterval(() => setIndex((i) => (i + 1) % count), 5500);
    return () => clearInterval(id);
  }, [count, paused]);
  useEffect(() => { if (index >= count) setIndex(0); }, [index, count]);

  if (!count) return null;
  const move = (step) => setIndex((i) => (i + step + count) % count);

  return (
    <section className="hero" onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}>
      {concerts.map((c, i) => {
        const state = saleState(c);
        return (
          <div key={c.id} className={`hero-slide ${i === index ? 'on' : ''}`} aria-hidden={i !== index}
            style={{ '--c1': c.palette[0], '--c2': c.palette[1], '--c3': c.palette[2] }}>
            <div className="hero-bg" />
            <div className="wrap hero-inner">
              <div className="hero-copy">
                <div className="hero-tags"><SaleBadge state={state} solid /><span className="tag light">{c.genreLabel}</span><span className="tag light">{dday(c.eventStart)}</span></div>
                <h2>{c.title}</h2>
                {c.tagline && <p className="hero-tagline">{c.tagline}</p>}
                <ul className="hero-facts">
                  <li><Icon name="calendar" size={17} /> {when(c.eventStart)}</li>
                  <li><Icon name="pin" size={17} /> {c.venue}</li>
                </ul>
                <div className="hero-cta">
                  <Link to={`/concert/${c.id}`} className="btn big light" tabIndex={i === index ? 0 : -1}>
                    {state === 'open' ? '예매하러 가기' : '공연 정보 보기'}
                  </Link>
                  {c.minPrice != null && <span className="hero-price">{won(c.minPrice)}부터</span>}
                </div>
              </div>
              <Link to={`/concert/${c.id}`} className="hero-poster" tabIndex={-1}><Poster concert={c} size="lg" /></Link>
            </div>
          </div>
        );
      })}
      {count > 1 && (
        <div className="wrap hero-nav">
          <button onClick={() => move(-1)} aria-label="이전 배너"><Icon name="left" /></button>
          <span className="hero-count"><b>{index + 1}</b> / {count}</span>
          <button onClick={() => move(1)} aria-label="다음 배너"><Icon name="right" /></button>
          <div className="hero-dots">
            {concerts.map((c, i) => <button key={c.id} className={i === index ? 'on' : ''} onClick={() => setIndex(i)} aria-label={`${i + 1}번째 배너`} />)}
          </div>
        </div>
      )}
    </section>
  );
}

export default function Home() {
  const { concerts, loading, error } = useConcerts('', 15000);
  const [genre, setGenre] = useState('all');

  const live = useMemo(() => concerts.filter((c) => saleState(c) !== 'ended'), [concerts]);
  // 배너: 예매중인 공연 먼저, 그다음 오픈예정
  const featured = useMemo(() => {
    const order = { open: 0, soldout: 1, upcoming: 2, ready: 3 };
    return [...live].sort((a, b) => order[saleState(a)] - order[saleState(b)] || new Date(a.eventStart) - new Date(b.eventStart)).slice(0, 5);
  }, [live]);
  const ranking = useMemo(() => [...live].filter((c) => c.totalSeats > 0)
    .sort((a, b) => b.booked - a.booked || bookedRate(b) - bookedRate(a)).slice(0, 5), [live]);
  const upcoming = useMemo(() => live.filter((c) => saleState(c) === 'upcoming')
    .sort((a, b) => new Date(a.eventOpen) - new Date(b.eventOpen)).slice(0, 4), [live]);
  const soon = useMemo(() => live.filter((c) => ['open', 'soldout'].includes(saleState(c)))
    .sort((a, b) => new Date(a.eventStart) - new Date(b.eventStart)).slice(0, 4), [live]);
  const genres = GENRES.filter((g) => live.some((c) => c.genre === g.key));
  const byGenre = live.filter((c) => genre === 'all' || c.genre === genre).slice(0, 10);

  return (
    <div className="home">
      <Hero concerts={featured} />

      <div className="wrap">
        {error && <div className="card error">{error}</div>}
        {loading && !concerts.length && <div className="empty">공연을 불러오는 중…</div>}
        {!loading && !error && !concerts.length && (
          <div className="empty">등록된 공연이 없습니다. 운영자 화면에서 공연을 등록해 주세요.</div>
        )}

        <nav className="genre-row" aria-label="장르">
          {GENRES.map((g) => (
            <Link key={g.key} to={`/concerts?genre=${g.key}`} className="genre-link">
              <span className="genre-icon" style={{ background: `linear-gradient(140deg, ${g.palette[1]}, ${g.palette[0]})` }}>{g.label.slice(0, 1)}</span>
              {g.label}
            </Link>
          ))}
        </nav>

        {ranking.length > 0 && (
          <section className="section">
            <div className="section-head">
              <div><h2>실시간 예매 랭킹</h2><p className="muted">지금 가장 많이 예매된 공연</p></div>
              <Link to="/concerts?sort=rank" className="more">전체 보기 <Icon name="right" size={15} /></Link>
            </div>
            <div className="rank-grid">
              {ranking.map((c, i) => <ConcertCard key={c.id} concert={c} rank={i + 1} />)}
            </div>
          </section>
        )}

        {upcoming.length > 0 && (
          <section className="section">
            <div className="section-head">
              <div><h2>티켓 오픈 예정</h2><p className="muted">오픈 시각에 대기열이 열립니다</p></div>
              <Link to="/concerts?state=upcoming" className="more">전체 보기 <Icon name="right" size={15} /></Link>
            </div>
            <div className="open-list">
              {upcoming.map((c) => {
                const d = dateParts(c.eventOpen);
                const left = daysUntil(c.eventOpen);
                return (
                  <Link to={`/concert/${c.id}`} key={c.id} className="open-item">
                    <div className="open-date"><b>{d.month}.{d.date}</b><span>{d.weekday}요일 {clock(c.eventOpen)}</span></div>
                    <Poster concert={c} size="xs" bare />
                    <div className="open-info">
                      <span className="tag">{c.genreLabel}</span>
                      <strong>{c.title}</strong>
                      <span className="muted">{c.venue} · {when(c.eventStart)}</span>
                    </div>
                    <span className="open-dday">{left === 0 ? '오늘 오픈' : `오픈 D-${left}`}</span>
                  </Link>
                );
              })}
            </div>
          </section>
        )}
      </div>

      <section className="trust">
        <div className="wrap">
          <div className="trust-head">
            <span className="tag light">FairChain</span>
            <h2>표를 구하는 순간부터 입장까지, 기록으로 지킵니다</h2>
          </div>
          <div className="trust-grid">
            {TRUST.map(([icon, title, text]) => (
              <div className="trust-item" key={title}>
                <span className="trust-icon"><Icon name={icon} size={24} /></span>
                <strong>{title}</strong>
                <p>{text}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      <div className="wrap">
        {soon.length > 0 && (
          <section className="section">
            <div className="section-head">
              <div><h2>곧 열리는 공연</h2><p className="muted">공연일이 가까운 순서</p></div>
              <Link to="/concerts?sort=soon" className="more">전체 보기 <Icon name="right" size={15} /></Link>
            </div>
            <div className="card-grid four">
              {soon.map((c) => <ConcertCard key={c.id} concert={c} />)}
            </div>
          </section>
        )}

        {live.length > 0 && (
          <section className="section">
            <div className="section-head">
              <div><h2>장르별 공연</h2></div>
              <div className="tabs">
                <button className={genre === 'all' ? 'on' : ''} onClick={() => setGenre('all')}>전체</button>
                {genres.map((g) => <button key={g.key} className={genre === g.key ? 'on' : ''} onClick={() => setGenre(g.key)}>{g.label}</button>)}
              </div>
            </div>
            <div className="card-grid">
              {byGenre.map((c) => <ConcertCard key={c.id} concert={c} />)}
            </div>
          </section>
        )}
      </div>
    </div>
  );
}
