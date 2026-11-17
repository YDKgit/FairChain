import { Link } from 'react-router-dom';
import Poster from './Poster.jsx';
import { SaleBadge } from './Badge.jsx';
import { bookedRate, dday, saleState, when, won } from '../utils.js';

/** 공연 목록의 카드 한 장. rank를 주면 포스터 위에 순위를 얹는다 */
export default function ConcertCard({ concert: c, rank }) {
  const state = saleState(c);
  const rate = bookedRate(c);
  return (
    <Link to={`/concert/${c.id}`} className={`ccard ${state === 'ended' ? 'is-ended' : ''}`}>
      <Poster concert={c}>
        {rank != null && <span className="ccard-rank">{rank}</span>}
        {state !== 'ended' && <span className="ccard-dday">{dday(c.eventStart)}</span>}
        {state === 'soldout' && <span className="ccard-cover">매진</span>}
      </Poster>
      <div className="ccard-body">
        <div className="ccard-tags"><SaleBadge state={state} /><span className="tag">{c.genreLabel}</span></div>
        <div className="ccard-title">{c.title}</div>
        <div className="ccard-venue">{c.venue}</div>
        <div className="ccard-date">{when(c.eventStart)}</div>
        <div className="ccard-foot">
          {state === 'upcoming'
            ? <span className="ccard-open">{when(c.eventOpen)} 오픈</span>
            : <span className="ccard-price">{c.minPrice != null ? <><b>{won(c.minPrice)}</b>부터</> : '가격 미정'}</span>}
          {state === 'open' && c.emptySeats <= 10 && <span className="ccard-left">잔여 {c.emptySeats}석</span>}
          {state === 'open' && c.emptySeats > 10 && rate > 0 && <span className="ccard-rate">예매율 {rate}%</span>}
        </div>
      </div>
    </Link>
  );
}
