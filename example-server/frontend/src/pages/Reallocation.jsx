import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { fetchConcerts, get } from '../api.js';
import { useAuth } from '../auth.jsx';
import { useRequireLogin, usePolling } from '../hooks.js';
import Countdown from '../components/Countdown.jsx';
import Icon from '../components/Icon.jsx';
import { ErrorBox } from '../components/PageState.jsx';
import Poster from '../components/Poster.jsx';
import { saleState, when } from '../utils.js';

/** 취소표 알림 — 나에게 온 제안과, 내가 명단에 올라 있는 공연의 현황 */
export default function Reallocation() {
  const loggedIn = useRequireLogin('/reallocation');
  const { config } = useAuth();
  const [offers, setOffers] = useState([]);
  const [statuses, setStatuses] = useState([]);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const [o, concerts] = await Promise.all([get('/api/reallocation/offers'), fetchConcerts()]);
      setOffers(o);
      const withStatus = await Promise.all(concerts.map(async (c) => ({
        ...c, realloc: await get(`/api/reallocation/concerts/${c.id}`).catch(() => null),
      })));
      setStatuses(withStatus.filter((c) => c.realloc && (saleState(c) === 'soldout' || c.realloc.heldCount > 0 || c.realloc.myRank)));
    } catch (e) { setError(e.message); }
  }, []);
  usePolling(load, 2000, loggedIn);

  const r = config.reallocation;
  if (error) return <ErrorBox message={error} />;

  return (
    <div className="wrap mypage">
      <header className="mypage-head">
        <div>
          <h1>취소표 알림</h1>
          <p className="muted">매진된 공연의 취소표는 새로고침 경쟁 없이 대기 순번대로 돌아갑니다.</p>
        </div>
      </header>

      <ol className="timeline">
        <li><b>취소 발생</b><span>매진 뒤 나온 취소표는 바로 풀지 않고 모아 둡니다.</span></li>
        <li><b>매일 {r.offerTime}</b><span>대기 순번(FairChain 명단) 순으로 한 사람씩 {r.offerTtlMinutes}분간 제안합니다.</span></li>
        <li><b>다음 날 {r.releaseTime}</b><span>명단을 다 돌고 남은 표는 일반 판매로 풀립니다.</span></li>
        <li><b>공연 {r.finalWindowHours}시간 전</b><span>이때부터는 취소표가 나오는 즉시 판매됩니다.</span></li>
      </ol>

      <section className="section">
        <div className="section-head"><div><h2>내 차례</h2></div></div>
        {!offers.length && <div className="empty">지금 나에게 온 제안이 없습니다.</div>}
        {offers.map((o) => (
          <div className="offer" key={o.offerId}>
            <Poster title={o.concertTitle} size="xs" bare />
            <div className="offer-info">
              <strong>{o.concertTitle}</strong>
              <span>취소표 {o.seats.length}장 중 하나를 고를 수 있습니다</span>
              <div className="chips">{o.seats.map((s) => <span className="chip" key={s.seatId}>{s.gradeName}석 {s.seatNumber}</span>)}</div>
              <span className="muted small">제안 {when(o.offeredAt)} · 기한 {when(o.expiresAt)}</span>
            </div>
            <div className="offer-side">
              <span className="muted small">남은 시간</span>
              <Countdown until={o.expiresAt} onExpire={load} />
              <Link className="btn" to={`/seats/${o.concertId}?offer=${o.offerToken}`}>취소표 고르기</Link>
            </div>
          </div>
        ))}
      </section>

      <section className="section">
        <div className="section-head"><div><h2>재배정 현황</h2><p className="muted">매진되었거나 취소표가 보류 중인 공연</p></div></div>
        {!statuses.length && <div className="empty">매진되었거나 취소표가 보류 중인 공연이 없습니다.</div>}
        <div className="realloc-list">
          {statuses.map((c) => {
            const s = c.realloc;
            return (
              <Link to={`/concert/${c.id}`} className="realloc-item" key={c.id}>
                <Poster concert={c} size="xs" bare />
                <div className="realloc-info">
                  <strong>{c.title}</strong>
                  <span className="muted small">{c.venue} · {when(c.eventStart)}</span>
                  <div className="chips">
                    <span className="chip">보류 취소표 {s.heldCount}장</span>
                    {s.roundStatus === 'OFFERING' && <span className="chip on">제안 진행 중 · {s.currentSequence ?? '-'}번 차례</span>}
                    {s.roundStatus === 'RELEASE_PENDING' && <span className="chip">{when(s.releaseAt)} 일반 판매</span>}
                    {s.nextOfferAt && <span className="chip">다음 제안 {when(s.nextOfferAt)}</span>}
                    {s.finalWindow && <span className="chip">공연 임박 — 바로 판매</span>}
                  </div>
                </div>
                <div className="realloc-rank">
                  {s.myOffer ? <><Icon name="bell" /><b className="accent">지금 내 차례</b></>
                    : s.myRank ? <><span className="muted small">취소표 차례</span><b>{s.myRank}번째</b>
                        {s.myQueueNumber && <span className="muted small">대기열 {s.myQueueNumber}번</span>}</>
                      : <span className="muted small">명단에 없음</span>}
                </div>
              </Link>
            );
          })}
        </div>
        <p className="muted small">대기열을 통과하고도 좌석을 못 구한 분만 명단에 오릅니다. 취소표 차례는 그분들 사이의 순서이고, 대기열 번호는 처음 줄을 선 순번입니다.</p>
      </section>
    </div>
  );
}
