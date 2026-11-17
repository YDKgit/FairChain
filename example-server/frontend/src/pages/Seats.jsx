import { useCallback, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { fetchConcert, get, post } from '../api.js';
import { useAuth } from '../auth.jsx';
import SeatMap from '../components/SeatMap.jsx';
import Countdown from '../components/Countdown.jsx';
import Icon from '../components/Icon.jsx';
import Poster from '../components/Poster.jsx';
import { ErrorBox, Loading } from '../components/PageState.jsx';
import { TicketCard, TicketModal } from '../components/Ticket.jsx';
import { useRequireLogin, usePolling } from '../hooks.js';
import { when, won } from '../utils.js';

/** 예매 단계 표시 — 대기열 → 좌석 선택 → 예매 완료 */
function Steps({ step, queue }) {
  const names = queue ? ['대기열', '좌석 선택', '예매 완료'] : ['좌석 선택', '예매 완료'];
  const current = queue ? step : step - 1;
  return (
    <ol className="steps-bar">
      {names.map((n, i) => (
        <li key={n} className={i < current ? 'done' : i === current ? 'on' : ''}>
          <span>{i < current ? <Icon name="check" size={14} stroke={2.6} /> : i + 1}</span>{n}
        </li>
      ))}
    </ol>
  );
}

/** 예매 완료 — 방금 받은 티켓이 블록체인에 기록되어 "검증 완료"로 바뀌는 것을 보여 준다 */
function Done({ concert, ticketId, queue }) {
  const [ticket, setTicket] = useState(null);
  const [open, setOpen] = useState(null);
  const load = useCallback(async () => {
    try { setTicket(await get(`/api/tickets/${ticketId}`)); } catch { /* 다음 주기에 다시 */ }
  }, [ticketId]);
  usePolling(load, 2000);
  const verified = ticket?.verificationStatus === 'VERIFIED';

  return (
    <div className="wrap done-page">
      <Steps step={2} queue={queue} />
      <div className="done-head">
        <span className="done-mark"><Icon name="check" size={34} stroke={2.6} /></span>
        <h1>예매가 완료되었습니다</h1>
        <p className="muted">
          {verified
            ? '티켓의 해시값이 블록체인 노드에 기록되어 검증까지 끝났습니다.'
            : '티켓의 해시값을 블록체인 노드에 기록하는 중입니다. 몇 초 뒤 "검증 완료"로 바뀝니다.'}
        </p>
      </div>
      {ticket
        ? <TicketCard ticket={ticket} concert={concert} onOpen={setOpen} onCancel={() => {}} />
        : <div className="empty">티켓을 불러오는 중…</div>}
      <div className="row gap center">
        <Link className="btn big" to="/tickets">내 티켓으로</Link>
        <Link className="btn big ghost" to="/">다른 공연 보기</Link>
      </div>
      <TicketModal ticket={open} concert={concert} onClose={() => setOpen(null)} />
    </div>
  );
}

/** 좌석 선택 · 결제(예매). ?offer=토큰 이면 재배정 제안으로 취소표만 고른다 */
export default function Seats() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const offerToken = params.get('offer');
  const navigate = useNavigate();
  const { config, toast } = useAuth();
  const loggedIn = useRequireLogin(`/seats/${id}${offerToken ? `?offer=${offerToken}` : ''}`);
  const [concert, setConcert] = useState(null);
  const [seats, setSeats] = useState([]);
  const [offer, setOffer] = useState(null);
  const [selected, setSelected] = useState(null);
  const [busy, setBusy] = useState(false);
  const [doneId, setDoneId] = useState(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const [c, s] = await Promise.all([fetchConcert(id), get(`/api/concerts/${id}/seats`)]);
      setConcert(c);
      setSeats(s.seats);
      if (offerToken) {
        const offers = await get('/api/reallocation/offers');
        setOffer(offers.find((o) => o.offerToken === offerToken) || null);
      }
    } catch (e) { setError(e.message); }
  }, [id, offerToken]);

  usePolling(load, 3000, loggedIn && !doneId);

  const offerSeatIds = new Set((offer?.seats || []).map((s) => s.seatId));
  const selectable = (s) => (offerToken ? offerSeatIds.has(s.seatId) && s.status === 'CANCELLED' : s.status === 'EMPTY');
  const chosen = seats.find((s) => s.seatId === selected) || null;
  const queue = config.queueEnabled && !offerToken;

  const reserve = async () => {
    if (!chosen) return;
    setBusy(true);
    try {
      const result = await post('/api/reservations', { seatId: chosen.seatId, concertId: Number(id), offerToken: offerToken || null });
      if (result.success) {
        setDoneId(result.ticketId);
        toast('예매가 완료되었습니다 — FairChain에 판매 기록을 보냈습니다');
      } else {
        toast(result.reason, 'error');
        setSelected(null);
        load();
      }
    } catch (e) { toast(e.message, 'error'); }
    finally { setBusy(false); }
  };

  if (error) return <ErrorBox message={error} />;
  if (!concert) return <Loading />;
  if (doneId) return <Done concert={concert} ticketId={doneId} queue={queue} />;

  const leftOf = (gradeName) => seats.filter((s) => s.gradeName === gradeName && selectable(s)).length;

  return (
    <div className="wrap seats-page">
      <Steps step={1} queue={queue} />
      <div className="seats-grid">
        <section className="panel seats-main">
          <header className="seats-head">
            <div>
              <h1>{concert.title}</h1>
              <p className="muted">{concert.venue} · {when(concert.eventStart)}</p>
            </div>
            <ul className="grade-legend">
              {concert.seatGrades.map((g) => (
                <li key={g.id}><i className={`dot grade-${g.gradeName.toLowerCase()}`} />{g.gradeName}석 <b>{won(g.price)}</b> <span>{leftOf(g.gradeName)}석</span></li>
              ))}
            </ul>
          </header>
          {offerToken && (
            offer ? (
              <div className="offer-callout">
                <b>취소표 재배정 제안</b> 아래 취소표 {offer.seats.length}장 중 하나를 고르세요. 남은 시간
                <Countdown until={offer.expiresAt} onExpire={() => { toast('제안 기한이 지났습니다', 'error'); navigate('/reallocation'); }} />
              </div>
            ) : <div className="error">유효한 제안이 아닙니다 (기한이 지났거나 이미 사용됨). <Link to="/reallocation" className="textlink">취소표 알림</Link></div>
          )}
          <SeatMap seats={seats} selected={selected} onSelect={(s) => setSelected(s.seatId)} selectable={selectable} />
        </section>

        <aside className="seats-side">
          <div className="paybox">
            <div className="paybox-concert">
              <Poster concert={concert} size="xs" bare />
              <div><strong>{concert.title}</strong><span>{when(concert.eventStart)}</span></div>
            </div>
            <h3>선택한 좌석</h3>
            {chosen ? (
              <>
                <div className="chosen">
                  <span>{chosen.gradeName}석</span>
                  <b>{chosen.seatNumber}</b>
                </div>
                <dl className="bill">
                  <div><dt>티켓 금액</dt><dd>{won(chosen.price)}</dd></div>
                  <div><dt>예매 수수료</dt><dd>0원</dd></div>
                  <div className="total"><dt>총 결제 금액</dt><dd>{won(chosen.price)}</dd></div>
                </dl>
              </>
            ) : (
              <div className="chosen empty-chosen">배치도에서 좌석을 고르세요</div>
            )}
            <button className="btn big full" disabled={!chosen || busy || (offerToken && !offer)} onClick={reserve}>
              {busy ? '처리 중…' : chosen ? `${won(chosen.price)} 결제하고 예매` : '좌석을 선택해 주세요'}
            </button>
            <p className="paybox-note">1인 1매 · 같은 좌석을 동시에 고르면 한 명만 성공합니다. 시연용이라 실제 결제는 없습니다.</p>
          </div>
        </aside>
      </div>
    </div>
  );
}
