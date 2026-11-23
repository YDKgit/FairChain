import { useState } from 'react';
import { del, put } from '../../api.js';
import { when } from '../../utils.js';

const toLocal = (t) => (t ? String(t).slice(0, 16) : '');

/** 공연 목록 · 수정 · 삭제. 새 공연은 '공연 등록' 탭에서 좌석 배치와 함께 만든다 */
export default function ConcertPanel({ concerts, reload, toast }) {
  const [form, setForm] = useState(null);          // 수정 중인 공연 (없으면 null)
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    try {
      await put(`/api/operator/concerts/${form.id}`, { title: form.title, venue: form.venue, eventOpen: form.eventOpen, eventStart: form.eventStart, eventEnd: form.eventEnd });
      toast('수정했습니다');
      setForm(null); reload();
    } catch (err) { toast(err.message, 'error'); }
  };
  const edit = (c) => setForm({ id: c.id, title: c.title, venue: c.venue, eventOpen: toLocal(c.eventOpen), eventStart: toLocal(c.eventStart), eventEnd: toLocal(c.eventEnd) });
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
        {form ? (
          <>
            <h3>공연 수정 (#{form.id})</h3>
            <form className="form" onSubmit={submit}>
              <label>제목<input value={form.title} onChange={set('title')} required /></label>
              <label>공연장<input value={form.venue} onChange={set('venue')} required /></label>
              <label>예매 오픈<input type="datetime-local" value={form.eventOpen} onChange={set('eventOpen')} required /></label>
              <label>공연 시작<input type="datetime-local" value={form.eventStart} onChange={set('eventStart')} required /></label>
              <label>공연 종료<input type="datetime-local" value={form.eventEnd} onChange={set('eventEnd')} required /></label>
              <div className="row gap">
                <button className="btn">저장</button>
                <button type="button" className="btn ghost" onClick={() => setForm(null)}>취소</button>
              </div>
            </form>
          </>
        ) : (
          <>
            <h3>공연 수정 · 삭제</h3>
            <p className="muted">목록에서 <b>수정</b>을 누르면 제목 · 공연장 · 시간을 바꿀 수 있습니다.</p>
            <p className="muted">새 공연은 <b>공연 등록</b> 탭에서 좌석 배치와 함께 만듭니다. 좌석이 등록된 공연은 FairChain에 기록이 남아 있어 삭제할 수 없습니다.</p>
          </>
        )}
      </div>
    </div>
  );
}
