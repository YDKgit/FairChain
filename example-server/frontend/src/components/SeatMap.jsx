import { useEffect, useMemo, useRef, useState } from 'react';
import { splitSeat } from '../utils.js';

/**
 * 좌석 배치도 — 공연장처럼 무대를 중심으로 부채꼴(아레나)로 그린다.
 * 열마다 좌석 수가 달라도 되고, 열은 가운데 정렬 · 가운데 통로 · 바깥쪽일수록 아래로 휘는 호(弧) 모양.
 * seats: [{seatId, seatNumber, gradeName, price, status}]
 * selectable(seat) → 고를 수 있는 좌석인지, selected → 선택한 seatId
 */
export default function SeatMap({ seats, selected, onSelect, selectable, compact = false }) {
  const ref = useRef(null);
  const [width, setWidth] = useState(720);

  useEffect(() => {
    if (!ref.current) return undefined;
    const ro = new ResizeObserver((entries) => setWidth(entries[0].contentRect.width));
    ro.observe(ref.current);
    return () => ro.disconnect();
  }, []);

  const rows = useMemo(() => {
    const map = new Map();
    for (const s of seats) {
      const { row, num } = splitSeat(s.seatNumber);
      if (!map.has(row)) map.set(row, []);
      map.get(row).push({ ...s, num });
    }
    return [...map.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([row, list]) => [row, list.sort((a, b) => a.num - b.num)]);
  }, [seats]);

  if (!seats.length) return <div className="empty">등록된 좌석이 없습니다</div>;

  const maxLen = Math.max(...rows.map(([, l]) => l.length));
  const gap = 3;
  const labelW = 26;
  const aisle = maxLen >= 8 ? 1.2 : 0;           // 가운데 통로 (좌석 너비 배수)
  const usable = width - labelW * 2 - 16;
  const size = Math.max(16, Math.min(compact ? 24 : 32, Math.floor((usable - gap * (maxLen - 1)) / (maxLen + aisle))));
  const small = size < 24;

  return (
    <div className={`seatmap arena ${compact ? 'compact' : ''}`} ref={ref} style={{ '--seat': `${size}px`, '--gap': `${gap}px` }}>
      <div className="stage-wrap">
        <div className="stage">STAGE</div>
      </div>
      {rows.map(([row, list], ri) => {
        const n = list.length;
        const half = (n - 1) / 2 || 1;
        const amp = size * 1.3 * (n / maxLen);          // 넓은 열일수록 더 많이 휜다
        const aisleAt = aisle ? Math.ceil(n / 2) : -1;   // 이 인덱스 앞에 통로
        return (
          <div className="seat-row" key={row} style={{ marginBottom: ri === rows.length - 1 ? Math.round(amp) : gap + 2 + Math.round(amp * 0.2) }}>
            <span className="row-label" style={{ transform: `translateY(${Math.round(amp)}px)` }}>{row}</span>
            <div className="seat-cells">
              {list.map((s, i) => {
                const x = (i - (n - 1) / 2) / half;      // -1 … 1
                const can = selectable ? selectable(s) : s.status === 'EMPTY';
                const cls = ['seat', s.status.toLowerCase(), can ? 'can' : 'no', selected === s.seatId ? 'selected' : '',
                  `grade-${(s.gradeName || '').toLowerCase()}`, small ? 'small' : ''].join(' ');
                return (
                  <button
                    key={s.seatId}
                    type="button"
                    className={cls}
                    disabled={!can}
                    title={`${s.seatNumber} · ${s.gradeName}석 · ${s.status}`}
                    onClick={() => can && onSelect && onSelect(s)}
                    style={{
                      marginLeft: i === aisleAt ? `${Math.round(size * aisle)}px` : undefined,
                      transform: `translateY(${(x * x * amp).toFixed(1)}px) rotate(${(x * 11).toFixed(1)}deg)`,
                    }}
                  >
                    {s.num}
                  </button>
                );
              })}
            </div>
            <span className="row-label" style={{ transform: `translateY(${Math.round(amp)}px)` }}>{row}</span>
          </div>
        );
      })}
      <div className="seat-legend">
        <span><i className="seat empty can" /> 예매 가능</span>
        <span><i className="seat reserved" /> 예매됨</span>
        <span><i className="seat cancelled" /> 취소표 (재배정 중)</span>
        <span><i className="seat selected can" /> 선택</span>
      </div>
    </div>
  );
}
