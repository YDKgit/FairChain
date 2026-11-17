import { useCallback, useEffect, useState } from 'react';
import { get, post } from '../../api.js';
import SeatMap from '../../components/SeatMap.jsx';
import { won } from '../../utils.js';

/** 좌석 사전 등록 — 좌석마다 FairChain에 발행(ISSUE) 기록이 남는다 */
export default function SeatPanel({ concerts, reload, toast }) {
  const [concertId, setConcertId] = useState('');
  const [grades, setGrades] = useState([]);
  const [gradeId, setGradeId] = useState('');
  const [numbers, setNumbers] = useState('A1-A10');
  const [seats, setSeats] = useState([]);
  const [busy, setBusy] = useState(false);

  useEffect(() => { if (!concertId && concerts.length) setConcertId(String(concerts[0].id)); }, [concerts, concertId]);
  const loadSeats = useCallback(async () => {
    if (!concertId) return;
    const c = concerts.find((x) => String(x.id) === concertId);
    setGrades(c?.seatGrades || []);
    setGradeId((g) => g || String(c?.seatGrades?.[0]?.id || ''));
    setSeats((await get(`/api/concerts/${concertId}/seats`)).seats);
  }, [concertId, concerts]);
  useEffect(() => { loadSeats().catch(() => {}); }, [loadSeats]);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      const r = await post('/api/operator/seats', { concertId: Number(concertId), seatGradeId: Number(gradeId), seatNumbers: [numbers] });
      toast(`${r.registered.length}석 등록 — FairChain에 발행 기록을 보냈습니다`);
      await loadSeats(); reload();
    } catch (err) { toast(err.message, 'error'); }
    finally { setBusy(false); }
  };
  const count = (st) => seats.filter((s) => s.status === st).length;

  // 시연 준비용 — 등급마다 정해 둔 줄을 한 번에 등록한다 (첫 등급은 앞줄 A·B, 나머지는 뒤쪽 C~E)
  const quickLayout = async (targets) => {
    setBusy(true);
    let total = 0;
    try {
      for (const c of targets) {
        const rows = [['A1-A10', 'B1-B12'], ['C1-C14', 'D1-D14', 'E1-E16']];
        for (const [i, g] of (c.seatGrades || []).slice(0, 2).entries()) {
          const r = await post('/api/operator/seats', { concertId: c.id, seatGradeId: g.id, seatNumbers: rows[i] });
          total += r.registered.length;
        }
      }
      toast(`${targets.length}개 공연에 ${total}석 등록 — FairChain에 발행 기록을 보냈습니다`);
    } catch (err) { toast(`${total}석 등록 후 중단: ${err.message}`, 'error'); }
    finally { setBusy(false); await loadSeats().catch(() => {}); reload(); }
  };
  const current = concerts.find((x) => String(x.id) === concertId);
  const seatless = concerts.filter((c) => !c.totalSeats);

  return (
    <div className="cols">
      <div className="card">
        <h3>좌석 사전 등록</h3>
        <p className="muted small">좌석을 등록하면 좌석마다 FairChain에 발행(ISSUE) 기록이 남습니다. 그래서 FairChain이 켜져 있어야 합니다.</p>
        <form className="form" onSubmit={submit}>
          <label>공연<select value={concertId} onChange={(e) => { setConcertId(e.target.value); setGradeId(''); }}>
            {concerts.map((c) => <option key={c.id} value={c.id}>{c.title}</option>)}</select></label>
          <label>좌석 등급<select value={gradeId} onChange={(e) => setGradeId(e.target.value)}>
            {grades.map((g) => <option key={g.id} value={g.id}>{g.gradeName} · {won(g.price)}</option>)}</select></label>
          <label>좌석 번호 (예: A1-A10, B1, B2)<input value={numbers} onChange={(e) => setNumbers(e.target.value)} required /></label>
          <button className="btn" disabled={busy}>{busy ? '등록 중…' : '등록 (FairChain에 발행 기록)'}</button>
        </form>
        <div className="quick-seats">
          <b className="small">시연 준비 — 기본 배치(66석)로 한 번에 등록</b>
          <span className="muted small">첫 등급 A · B열 22석, 둘째 등급 C · D · E열 44석. 이미 있는 좌석 번호는 서버가 거절합니다.</span>
          <div className="row gap wrap">
            <button type="button" className="btn small ghost" disabled={busy || !current || current.totalSeats > 0} onClick={() => quickLayout([current])}>
              이 공연에 등록
            </button>
            <button type="button" className="btn small ghost" disabled={busy || !seatless.length} onClick={() => quickLayout(seatless)}>
              좌석 없는 공연 {seatless.length}개 모두 등록
            </button>
          </div>
        </div>
      </div>
      <div className="card">
        <h3>좌석 현황</h3>
        <p className="muted small">예매 가능 {count('EMPTY')} · 예매됨 {count('RESERVED')} · 취소표 보류 {count('CANCELLED')}</p>
        <SeatMap seats={seats} selectable={() => false} compact />
      </div>
    </div>
  );
}
