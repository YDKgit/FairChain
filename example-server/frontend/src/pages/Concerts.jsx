import { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { GENRES, genreOf } from '../catalog.js';
import ConcertCard from '../components/ConcertCard.jsx';
import { useConcerts } from '../hooks.js';
import { bookedRate, saleState } from '../utils.js';

const STATES = [['', '전체'], ['open', '예매중'], ['upcoming', '오픈예정'], ['soldout', '매진'], ['ended', '종료']];
const SORTS = [['soon', '공연 임박순'], ['rank', '예매 많은 순'], ['price', '낮은 가격순'], ['open', '예매 오픈순']];

const SORTERS = {
  soon: (a, b) => new Date(a.eventStart) - new Date(b.eventStart),
  rank: (a, b) => b.booked - a.booked || bookedRate(b) - bookedRate(a),
  price: (a, b) => (a.minPrice ?? Infinity) - (b.minPrice ?? Infinity),
  open: (a, b) => new Date(a.eventOpen) - new Date(b.eventOpen),
};

/** 공연 목록 — 장르 · 판매 상태 · 지역으로 거르고 정렬한다. 주소의 ?genre= ?state= ?region= ?sort= ?q= 를 그대로 쓴다 */
export default function Concerts() {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') || '';
  const genre = params.get('genre') || '';
  const state = params.get('state') || '';
  const region = params.get('region') || '';
  const sort = params.get('sort') || 'soon';
  const { concerts, loading, error } = useConcerts(q, 15000);

  const set = (key, value) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value); else next.delete(key);
    setParams(next, { replace: true });
  };

  const regions = useMemo(() => [...new Set(concerts.map((c) => c.region).filter(Boolean))].sort(), [concerts]);
  const shown = useMemo(() => concerts
    .filter((c) => !genre || c.genre === genre)
    .filter((c) => !region || c.region === region)
    .filter((c) => {
      const s = saleState(c);
      if (!state) return s !== 'ended';            // 기본은 끝난 공연을 빼고 보여 준다
      return state === 'open' ? s === 'open' : s === state;
    })
    .sort(SORTERS[sort] || SORTERS.soon), [concerts, genre, region, state, sort]);

  const title = q ? `"${q}" 검색 결과` : genre ? genreOf(genre).label : state === 'upcoming' ? '티켓 오픈 예정' : sort === 'rank' ? '예매 랭킹' : '전체 공연';

  return (
    <div className="wrap list-page">
      <div className="list-head">
        <h1>{title}</h1>
        <span className="muted">{shown.length}개 공연</span>
      </div>

      <div className="filters">
        <div className="filter-line">
          <span className="filter-label">장르</span>
          <div className="chips">
            <button className={`chip ${!genre ? 'on' : ''}`} onClick={() => set('genre', '')}>전체</button>
            {GENRES.map((g) => <button key={g.key} className={`chip ${genre === g.key ? 'on' : ''}`} onClick={() => set('genre', g.key)}>{g.label}</button>)}
          </div>
        </div>
        <div className="filter-line">
          <span className="filter-label">상태</span>
          <div className="chips">
            {STATES.map(([k, label]) => <button key={k} className={`chip ${state === k ? 'on' : ''}`} onClick={() => set('state', k)}>{label}</button>)}
          </div>
        </div>
        {regions.length > 1 && (
          <div className="filter-line">
            <span className="filter-label">지역</span>
            <div className="chips">
              <button className={`chip ${!region ? 'on' : ''}`} onClick={() => set('region', '')}>전체</button>
              {regions.map((r) => <button key={r} className={`chip ${region === r ? 'on' : ''}`} onClick={() => set('region', r)}>{r}</button>)}
            </div>
          </div>
        )}
        <div className="filter-line">
          <span className="filter-label">정렬</span>
          <div className="sorts">
            {SORTS.map(([k, label]) => <button key={k} className={sort === k ? 'on' : ''} onClick={() => set('sort', k === 'soon' ? '' : k)}>{label}</button>)}
          </div>
        </div>
      </div>

      {error && <div className="card error">{error}</div>}
      {loading && !concerts.length ? (
        <div className="empty">공연을 불러오는 중…</div>
      ) : shown.length ? (
        <div className="card-grid">
          {shown.map((c, i) => <ConcertCard key={c.id} concert={c} rank={sort === 'rank' ? i + 1 : undefined} />)}
        </div>
      ) : (
        <div className="empty">조건에 맞는 공연이 없습니다.</div>
      )}
    </div>
  );
}
