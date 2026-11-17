import { useState } from 'react';
import { del, post, put } from '../../api.js';
import { when } from '../../utils.js';

const EMPTY = { title: '', venue: '', eventOpen: '', eventStart: '', eventEnd: '', grades: 'R:132000, S:88000' };
const toLocal = (t) => (t ? String(t).slice(0, 16) : '');

/** 공연 등록 · 수정 · 삭제 */
export default function ConcertPanel({ concerts, reload, toast }) {
  const [form, setForm] = useState(EMPTY);
  const [editing, setEditing] = useState(null);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    const seatGrades = form.grades.split(',').map((p) => p.split(':')).filter((p) => p.length === 2)
      .map(([g, p]) => ({ gradeName: g.trim(), price: Number(p.trim()) }));
    const body = { title: form.title, venue: form.venue, eventOpen: form.eventOpen, eventStart: form.eventStart, eventEnd: form.eventEnd, seatGrades };
    try {
      if (editing) { await put(`/api/operator/concerts/${editing}`, body); toast('수정했습니다'); }
      else { await post('/api/operator/concerts', body); toast('공연을 등록했습니다 — 좌석 등록 탭에서 좌석을 만들어 주세요'); }
      setForm(EMPTY); setEditing(null); reload();
    } catch (err) { toast(err.message, 'error'); }
  };
  const edit = (c) => {
    setEditing(c.id);
    setForm({ title: c.title, venue: c.venue, eventOpen: toLocal(c.eventOpen), eventStart: toLocal(c.eventStart), eventEnd: toLocal(c.eventEnd),
      grades: (c.seatGrades || []).map((g) => `${g.gradeName}:${g.price}`).join(', ') });
  };
  const remove = async (c) => {
    if (!window.confirm(`"${c.title}" 공연을 삭제할까요?`)) return;
    try { await del(`/api/operator/concerts/${c.id}`); toast('삭제했습니다'); reload(); } catch (err) { toast(err.message, 'error'); }
  };

  return (
    <div className="cols">
      <div className="card">
        <h3>공연 목록</h3>
        <table className="table"><thead><tr><th>#</th><th>제목</th><th>공연장</th><th>예매 오픈</th><th>공연</th><th>좌석</th><th></th></tr></thead>
          <tbody>
            {concerts.map((c) => (
              <tr key={c.id}>
                <td>{c.id}</td><td>{c.title}</td><td>{c.venue}</td><td>{when(c.eventOpen)}</td><td>{when(c.eventStart)}</td><td>{c.totalSeats ?? '-'}</td>
                <td className="row gap"><button className="btn small ghost" onClick={() => edit(c)}>수정</button><button className="btn small danger" onClick={() => remove(c)}>삭제</button></td>
              </tr>
            ))}
          </tbody></table>
      </div>
      <div className="card">
        <h3>{editing ? `공연 수정 (#${editing})` : '공연 등록'}</h3>
        <form className="form" onSubmit={submit}>
          <label>제목<input value={form.title} onChange={set('title')} required /></label>
          <label>공연장<input value={form.venue} onChange={set('venue')} required /></label>
          <label>예매 오픈<input type="datetime-local" value={form.eventOpen} onChange={set('eventOpen')} required /></label>
          <label>공연 시작<input type="datetime-local" value={form.eventStart} onChange={set('eventStart')} required /></label>
          <label>공연 종료<input type="datetime-local" value={form.eventEnd} onChange={set('eventEnd')} required /></label>
          {!editing && <label>좌석 등급 (등급:가격, …)<input value={form.grades} onChange={set('grades')} /></label>}
          <div className="row gap">
            <button className="btn">{editing ? '저장' : '등록'}</button>
            {editing && <button type="button" className="btn ghost" onClick={() => { setEditing(null); setForm(EMPTY); }}>취소</button>}
          </div>
        </form>
      </div>
    </div>
  );
}
