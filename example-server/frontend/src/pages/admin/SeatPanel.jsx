import { useCallback, useEffect, useState } from 'react';
import { get } from '../../api.js';
import SeatMap from '../../components/SeatMap.jsx';
import ConcertRegisterForm from './ConcertRegisterForm.jsx';

/** 공연 등록 — 왼쪽에서 새 공연과 좌석 배치를 만들어 등록하고, 오른쪽에서 등록된 좌석을 확인한다 */
export default function SeatPanel({ concerts, reload, toast }) {
  const [concertId, setConcertId] = useState('');
  const [seats, setSeats] = useState([]);

  useEffect(() => { if (!concertId && concerts.length) setConcertId(String(concerts[0].id)); }, [concerts, concertId]);
  const loadSeats = useCallback(async () => {
    if (!concertId) return;
    setSeats((await get(`/api/concerts/${concertId}/seats`)).seats);
  }, [concertId]);
  useEffect(() => { loadSeats().catch(() => {}); }, [loadSeats]);

  /** 등록이 끝나면 목록을 다시 받고, 방금 만든 공연의 좌석을 오른쪽에 보여 준다 */
  const registered = async (id) => {
    await reload();
    if (id) setConcertId(String(id)); else loadSeats().catch(() => {});
  };
  const count = (st) => seats.filter((s) => s.status === st).length;
  const vip = seats.filter((s) => s.gradeName === 'VIP').length;

  return (
    <div className="cols">
      <div className="card">
        <h3>새 공연 등록</h3>
        <p className="muted small">공연 정보와 좌석 배치를 정해 한 번에 등록합니다. 좌석마다 FairChain에 발행(ISSUE) 기록이 남으므로 FairChain이 켜져 있어야 합니다.</p>
        <ConcertRegisterForm toast={toast} onDone={registered} />
      </div>
      <div className="card">
        <h3>등록된 좌석</h3>
        <div className="form">
          <label>공연<select value={concertId} onChange={(e) => setConcertId(e.target.value)}>
            {concerts.map((c) => <option key={c.id} value={c.id}>#{c.id} {c.title}</option>)}</select></label>
        </div>
        <p className="muted small">전체 {seats.length}석 (VIP {vip}석) · 예매 가능 {count('EMPTY')} · 예매됨 {count('RESERVED')} · 취소표 보류 {count('CANCELLED')}</p>
        <SeatMap seats={seats} selectable={() => false} compact />
      </div>
    </div>
  );
}
