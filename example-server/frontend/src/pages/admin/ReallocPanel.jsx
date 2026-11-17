import { useCallback, useEffect, useState } from 'react';
import { get, post } from '../../api.js';
import SeatMap from '../../components/SeatMap.jsx';
import { usePolling } from '../../hooks.js';
import { durationText, hhmm, offerStatusText, roundStatusText } from '../../labels.js';
import { when } from '../../utils.js';

/** 취소표 재배정 — 보류 취소표, 진행 중인 회차 · 명단, 시연용 수동 진행 버튼 */
export default function ReallocPanel({ concerts, toast }) {
  const [concertId, setConcertId] = useState('');
  const [status, setStatus] = useState(null);
  const [seats, setSeats] = useState([]);
  const [busy, setBusy] = useState(false);

  useEffect(() => { if (!concertId && concerts.length) setConcertId(String(concerts[0].id)); }, [concerts, concertId]);

  const load = useCallback(async () => {
    if (!concertId) return;
    try {
      const [s, m] = await Promise.all([get(`/api/operator/reallocation/${concertId}`), get(`/api/concerts/${concertId}/seats`)]);
      setStatus(s);
      setSeats(m.seats);
    } catch (e) { toast(e.message, 'error'); }
  }, [concertId, toast]);
  usePolling(load, 3000);

  const run = async (path, okMsg) => {
    setBusy(true);
    try { const r = await post(`/api/operator/reallocation/${concertId}/${path}`); toast(okMsg(r)); await load(); }
    catch (e) { toast(e.message, 'error'); }
    finally { setBusy(false); }
  };

  const sc = status?.schedule;
  return (
    <>
      <div className="card">
        <div className="row gap wrap between">
          <label className="row gap">공연
            <select value={concertId} onChange={(e) => setConcertId(e.target.value)}>
              {concerts.map((c) => <option key={c.id} value={c.id}>{c.title}</option>)}
            </select>
          </label>
          {sc && <span className="muted small">제안 {hhmm(sc.offerTime)} · 일반 판매 {hhmm(sc.releaseTime)} · 응답 기한 {durationText(sc.offerTtl)} · 공연 {durationText(sc.finalWindow)} 전부터 즉시 판매</span>}
        </div>
      </div>
      {status && (
        <div className="cols">
          <div className="card">
            <h3>보류 중인 취소표 <span className="pill">{status.heldSeats.length}</span></h3>
            {status.finalWindow && <p className="accent">공연 임박 — 취소표는 바로 일반 판매됩니다.</p>}
            {status.nextOfferAt && <p>다음 제안 시작: <b>{when(status.nextOfferAt)}</b></p>}
            {!status.heldSeats.length && <p className="muted">없음</p>}
            <ul className="plain">
              {status.heldSeats.map((s) => (
                <li key={s.seatId}><b>{s.seatNumber}</b> {s.gradeName}석 · 취소 {when(s.cancelledAt)} {s.inRound ? <span className="badge wait">이번 회차</span> : <span className="badge muted">다음 회차 대기</span>}</li>
              ))}
            </ul>
            <div className="row gap wrap" style={{ marginTop: 12 }}>
              <button className="btn" disabled={busy || !!status.round || !status.heldSeats.some((s) => !s.inRound)}
                onClick={() => run('offer-now', (r) => `회차 시작 — 명단 ${r.candidateCount}명에게 순번대로 제안합니다`)}>
                지금 명단에 제안 시작 ({hhmm(sc?.offerTime)} 대신)
              </button>
              <button className="btn ghost" disabled={busy || !status.heldSeats.length}
                onClick={() => window.confirm('보류 중인 취소표를 전부 일반 판매로 풀까요?') && run('release-now', (r) => `${r.released}장을 일반 판매로 풀었습니다`)}>
                지금 일반 판매로 풀기 ({hhmm(sc?.releaseTime)} 대신)
              </button>
            </div>
            <p className="muted small">시연용 버튼입니다. 실제로는 매일 {hhmm(sc?.offerTime)}과 다음 날 {hhmm(sc?.releaseTime)}에 자동으로 진행됩니다.</p>
          </div>
          <div className="card">
            <h3>진행 중인 회차</h3>
            {!status.round && <p className="muted">진행 중인 회차가 없습니다.</p>}
            {status.round && <RoundView round={status.round} />}
            {status.history.length > 0 && (
              <details style={{ marginTop: 12 }}><summary>지난 회차 {status.history.length}개</summary>
                {status.history.map((r) => <RoundView round={r} key={r.roundId} compact />)}
              </details>
            )}
          </div>
        </div>
      )}
      <div className="card">
        <h3>좌석 현황</h3>
        <SeatMap seats={seats} selectable={() => false} compact />
      </div>
    </>
  );
}

function RoundView({ round, compact }) {
  return (
    <div className="round">
      <div className="row gap wrap">
        <span className={`badge ${round.status === 'DONE' ? 'muted' : round.status === 'OFFERING' ? 'ok' : 'wait'}`}>{roundStatusText[round.status]}</span>
        <span className="small">회차 #{round.roundId} · 시작 {when(round.startedAt)}</span>
        <span className="small">취소표 {round.seatCount}장 (남음 {round.remainingSeats}) · 명단 {round.candidateCount}명</span>
        {round.releaseAt && <span className="small">일반 판매 {when(round.releaseAt)}</span>}
      </div>
      {!compact && (
        <table className="table small"><thead><tr><th>순번</th><th>회원</th><th>상태</th><th>제안</th><th>기한</th><th>좌석</th></tr></thead>
          <tbody>
            {round.offers.map((o) => (
              <tr key={o.sequenceNumber} className={o.status === 'OFFERED' ? 'hl' : ''}>
                <td>{o.sequenceNumber}</td><td>{o.memberName}</td>
                <td><span className={`badge ${o.status === 'OFFERED' ? 'ok' : o.status === 'ACCEPTED' ? 'wait' : 'muted'}`}>{offerStatusText[o.status]}</span></td>
                <td>{o.offeredAt ? when(o.offeredAt) : '-'}</td><td>{o.expiresAt ? when(o.expiresAt) : '-'}</td><td>{o.seatNumber || '-'}</td>
              </tr>
            ))}
            {!round.offers.length && <tr><td colSpan="6" className="muted">명단이 비어 있습니다</td></tr>}
          </tbody></table>
      )}
    </div>
  );
}
