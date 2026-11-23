import { useEffect, useRef } from 'react';

export const MAX_SEATS = 100;
const rowName = (i) => String.fromCharCode(65 + i);          // 0 → A … 9 → J

/** 줄 수 × 한 줄 좌석 수로 만든 좌석 번호 목록 (A1, A2, … 줄마다) */
export function seatNumbers(rows, perRow) {
  return Array.from({ length: rows }, (_, r) => Array.from({ length: perRow }, (_, n) => `${rowName(r)}${n + 1}`));
}

/**
 * 공연 등록 때 쓰는 좌석 배치 만들기 — 줄 수와 한 줄 좌석 수를 정하고(최대 100석),
 * 좌석을 누르거나 끌어서 VIP로 표시한다. 표시하지 않은 좌석은 일반 좌석이 된다.
 * value: { rows, perRow, vip: Set<좌석 번호> }, onChange(next)
 */
export default function SeatDesigner({ value, onChange }) {
  const { rows, perRow, vip } = value;
  const drag = useRef(null);                                   // 끄는 동안: true = VIP로 칠하기, false = 지우기

  useEffect(() => {
    const stop = () => { drag.current = null; };
    window.addEventListener('pointerup', stop);
    window.addEventListener('pointercancel', stop);
    return () => { window.removeEventListener('pointerup', stop); window.removeEventListener('pointercancel', stop); };
  }, []);

  const paint = (seat, on) => {
    if (vip.has(seat) === on) return;
    const next = new Set(vip);
    if (on) next.add(seat); else next.delete(seat);
    onChange({ ...value, vip: next });
  };
  const resize = (nextRows, nextPerRow) => {
    const r = Math.max(1, Math.min(10, nextRows || 1));
    const p = Math.max(1, Math.min(Math.floor(MAX_SEATS / r), nextPerRow || 1));
    const keep = new Set(seatNumbers(r, p).flat());
    onChange({ rows: r, perRow: p, vip: new Set([...vip].filter((s) => keep.has(s))) });
  };
  /** 손가락으로 끌 때는 pointerenter가 오지 않아, 손가락 아래의 좌석을 직접 찾는다 */
  const onMove = (e) => {
    if (drag.current === null || e.pointerType === 'mouse') return;
    const seat = document.elementFromPoint(e.clientX, e.clientY)?.dataset?.seat;
    if (seat) paint(seat, drag.current);
  };

  const grid = seatNumbers(rows, perRow);
  const total = rows * perRow;
  return (
    <div className="designer">
      <div className="row gap">
        <label>줄 수 (최대 10)<input type="number" min="1" max="10" value={rows} onChange={(e) => resize(Number(e.target.value), perRow)} /></label>
        <label>한 줄 좌석 수<input type="number" min="1" max={Math.floor(MAX_SEATS / rows)} value={perRow} onChange={(e) => resize(rows, Number(e.target.value))} /></label>
      </div>
      <p className="muted small">좌석을 <b>누르거나 끌어서</b> VIP로 표시하세요. 표시하지 않은 좌석은 일반 좌석이 됩니다. (전체 최대 {MAX_SEATS}석)</p>
      <div className="designer-stage">STAGE</div>
      <div className="designer-grid" onPointerMove={onMove}>
        {grid.map((line, r) => (
          <div className="designer-row" key={rowName(r)}>
            <span className="row-label">{rowName(r)}</span>
            {line.map((seat) => (
              <button type="button" key={seat} data-seat={seat} className={`designer-seat ${vip.has(seat) ? 'vip' : ''}`}
                onPointerDown={(e) => { e.preventDefault(); e.currentTarget.releasePointerCapture?.(e.pointerId); drag.current = !vip.has(seat); paint(seat, drag.current); }}
                onPointerEnter={() => { if (drag.current !== null) paint(seat, drag.current); }}>
                {seat.slice(1)}
              </button>
            ))}
          </div>
        ))}
      </div>
      <div className="designer-foot">
        <span><i className="designer-seat vip" /> VIP <b>{vip.size}</b>석</span>
        <span><i className="designer-seat" /> 일반 <b>{total - vip.size}</b>석</span>
        <span className="muted">전체 {total}석</span>
        <button type="button" className="btn small ghost" disabled={!vip.size} onClick={() => onChange({ ...value, vip: new Set() })}>VIP 표시 지우기</button>
      </div>
    </div>
  );
}
