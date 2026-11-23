import { useState } from 'react';
import { fetchConcert, post } from '../../api.js';
import SeatDesigner, { seatNumbers } from './SeatDesigner.jsx';

const EMPTY = { title: '', venue: '', eventOpen: '', eventStart: '', eventEnd: '', vipPrice: '165000', normalPrice: '99000' };
const EMPTY_LAYOUT = { rows: 5, perRow: 10, vip: new Set() };
/** datetime-local 입력에 넣을 "지금부터 minutes분 뒤" (이 컴퓨터 시각 기준) */
const localAfter = (minutes) => {
  const d = new Date(Date.now() + minutes * 60000);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
};

/**
 * 새 공연 등록 — 공연 정보와 좌석 배치를 한 화면에서 정하고 한 번에 등록한다.
 * VIP로 표시한 좌석은 VIP, 나머지는 일반 좌석. 좌석마다 FairChain에 발행 기록이 남는다.
 * onDone(새 공연 id)
 */
export default function ConcertRegisterForm({ toast, onDone }) {
  const [form, setForm] = useState(EMPTY);
  const [layout, setLayout] = useState(EMPTY_LAYOUT);
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  /** 시연용 시간: 지금 바로 예매할 수 있고, 오늘 입장(체크인)할 수 있는 공연 — 입장은 공연 시작 3시간 전부터 열린다 */
  const demoTimes = () => setForm({ ...form, eventOpen: localAfter(-1), eventStart: localAfter(60), eventEnd: localAfter(180) });

  const submit = async (e) => {
    e.preventDefault();
    const all = seatNumbers(layout.rows, layout.perRow).flat();
    const vip = all.filter((s) => layout.vip.has(s));
    const normal = all.filter((s) => !layout.vip.has(s));
    const seatGrades = [...(vip.length ? [{ gradeName: 'VIP', price: Number(form.vipPrice) }] : []),
      ...(normal.length ? [{ gradeName: '일반', price: Number(form.normalPrice) }] : [])];
    setBusy(true);
    let id = null;
    try {
      ({ id } = await post('/api/operator/concerts', { title: form.title, venue: form.venue, eventOpen: form.eventOpen,
        eventStart: form.eventStart, eventEnd: form.eventEnd, seatGrades }));
      const grades = (await fetchConcert(id)).seatGrades || [];
      for (const [name, numbers] of [['VIP', vip], ['일반', normal]]) {
        const grade = grades.find((g) => g.gradeName === name);
        if (grade && numbers.length) await post('/api/operator/seats', { concertId: id, seatGradeId: grade.id, seatNumbers: numbers });
      }
      toast(`공연을 등록했습니다 — VIP ${vip.length}석 · 일반 ${normal.length}석 (FairChain에 발행 기록)`);
      setForm(EMPTY); setLayout(EMPTY_LAYOUT);
    } catch (err) { toast(err.message, 'error'); }
    finally { setBusy(false); onDone?.(id); }
  };

  return (
    <form className="form" onSubmit={submit}>
      <label>제목<input value={form.title} onChange={set('title')} required /></label>
      <label>공연장<input value={form.venue} onChange={set('venue')} required /></label>
      <div className="row gap">
        <button type="button" className="btn small ghost" onClick={demoTimes}>시연용 시간 채우기</button>
        <span className="muted small">지금 바로 예매 · 오늘 입장할 수 있는 시간으로 채웁니다</span>
      </div>
      <label>예매 오픈<input type="datetime-local" value={form.eventOpen} onChange={set('eventOpen')} required /></label>
      <label>공연 시작<input type="datetime-local" value={form.eventStart} onChange={set('eventStart')} required /></label>
      <label>공연 종료<input type="datetime-local" value={form.eventEnd} onChange={set('eventEnd')} required /></label>
      <div className="row gap">
        <label>VIP 가격<input type="number" min="0" value={form.vipPrice} onChange={set('vipPrice')} required /></label>
        <label>일반 가격<input type="number" min="0" value={form.normalPrice} onChange={set('normalPrice')} required /></label>
      </div>
      <SeatDesigner value={layout} onChange={setLayout} />
      <button className="btn" disabled={busy}>{busy ? '등록 중…' : '나머지는 일반 좌석으로 하고 등록'}</button>
    </form>
  );
}
