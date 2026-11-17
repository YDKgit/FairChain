import { useMemo } from 'react';
import { enrich } from '../catalog.js';
import { shortDate } from '../utils.js';

/** 제목에서 뽑은 씨앗으로 늘 같은 무늬가 나오게 하는 난수 */
function seeded(text = '') {
  let h = 2166136261;
  for (const ch of text) { h ^= ch.charCodeAt(0); h = Math.imul(h, 16777619); }
  let s = h >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** 포스터 무늬 — 300×400 좌표계. 장르마다 다른 무늬를 쓴다 */
const ART = {
  // 궤도: 큰 원과 겹친 고리
  orbit(r, [, light, point]) {
    const cx = 150 + (r() - 0.5) * 120;
    const cy = 130 + r() * 60;
    return (
      <>
        <circle cx={cx} cy={cy} r={70 + r() * 30} fill={point} opacity="0.9" />
        <circle cx={cx + 26} cy={cy - 18} r={64 + r() * 20} fill={light} opacity="0.55" />
        {[110, 150, 195, 245].map((rad, i) => (
          <ellipse key={rad} cx={cx} cy={cy} rx={rad} ry={rad * (0.34 + r() * 0.2)} fill="none" stroke="#fff"
            strokeOpacity={0.5 - i * 0.09} strokeWidth="1.4" transform={`rotate(${-24 + r() * 16} ${cx} ${cy})`} />
        ))}
        {Array.from({ length: 26 }, (_, i) => (
          <circle key={i} cx={r() * 300} cy={r() * 400} r={0.6 + r() * 1.6} fill="#fff" opacity={0.3 + r() * 0.6} />
        ))}
      </>
    );
  },
  // 물결: 겹겹이 쌓인 곡선
  wave(r, [, light, point]) {
    return (
      <>
        <circle cx={210 + r() * 40} cy={90 + r() * 40} r={38 + r() * 14} fill={point} opacity="0.85" />
        {Array.from({ length: 7 }, (_, i) => {
          const y = 150 + i * 38;
          const a = 14 + r() * 22;
          const shift = r() * 80;
          return (
            <path key={i} fill={i % 2 ? light : '#fff'} opacity={0.1 + i * 0.045}
              d={`M-20 ${y} C ${40 + shift} ${y - a}, ${110 + shift} ${y + a}, ${180} ${y} S ${290} ${y - a}, 330 ${y} V 420 H -20 Z`} />
          );
        })}
      </>
    );
  },
  // 조명: 아래에서 뻗는 빛줄기
  beam(r, [, light, point]) {
    return (
      <>
        {Array.from({ length: 9 }, (_, i) => {
          const angle = -64 + i * 16 + (r() - 0.5) * 8;
          return (
            <polygon key={i} points="150,410 138,-40 162,-40" fill={i % 2 ? point : light} opacity={0.2 + r() * 0.28}
              transform={`rotate(${angle} 150 410)`} />
          );
        })}
        <ellipse cx="150" cy="405" rx="170" ry="46" fill="#000" opacity="0.5" />
        {Array.from({ length: 30 }, (_, i) => (
          <rect key={i} x={i * 10 + r() * 4} y={372 - r() * 26} width="7" height="40" rx="3.5" fill="#000" opacity="0.62" />
        ))}
      </>
    );
  },
  // 격자: 굵은 사선과 점
  grid(r, [, light, point]) {
    return (
      <>
        {Array.from({ length: 6 }, (_, i) => (
          <rect key={i} x={-120 + i * 82} y="-60" width={24 + r() * 26} height="560" fill={i % 2 ? point : light}
            opacity={0.3 + r() * 0.4} transform="rotate(24 150 200)" />
        ))}
        {Array.from({ length: 8 }, (_, row) => Array.from({ length: 6 }, (_, col) => (
          <circle key={`${row}-${col}`} cx={176 + col * 20} cy={34 + row * 20} r={1.2 + ((row + col) % 4) * 1.1} fill="#fff" opacity="0.5" />
        )))}
      </>
    );
  },
  // 햇살: 반원과 방사선
  sun(r, [, light, point]) {
    return (
      <>
        {Array.from({ length: 18 }, (_, i) => (
          <polygon key={i} points="150,250 143,-160 157,-160" fill="#fff" opacity={i % 2 ? 0.16 : 0.07}
            transform={`rotate(${i * 20} 150 250)`} />
        ))}
        <circle cx="150" cy="250" r={78 + r() * 14} fill={point} />
        <circle cx="150" cy="250" r={56 + r() * 10} fill={light} opacity="0.5" />
        <path d={`M-10 ${292 + r() * 10} Q 70 ${256 + r() * 20} 150 292 T 310 286 V 410 H -10 Z`} fill="#000" opacity="0.34" />
        <path d={`M-10 ${330 + r() * 10} Q 90 ${298 + r() * 20} 170 330 T 310 322 V 410 H -10 Z`} fill="#000" opacity="0.4" />
      </>
    );
  },
  // 현: 가는 호가 겹친 무늬
  strings(r, [, light, point]) {
    return (
      <>
        {Array.from({ length: 16 }, (_, i) => (
          <path key={i} d={`M ${-30 + i * 9} 420 Q ${90 + i * 8} ${40 + r() * 60} ${340 - i * 5} ${-20 + i * 12}`}
            fill="none" stroke={i % 3 ? point : light} strokeWidth={0.8 + (i % 3) * 0.5} opacity={0.3 + (i % 4) * 0.14} />
        ))}
        <circle cx={96 + r() * 30} cy={270 + r() * 30} r="30" fill="none" stroke={point} strokeWidth="1.4" opacity="0.8" />
        <circle cx={96} cy={270} r="3.5" fill={point} />
      </>
    );
  },
  // 프리즘: 겹친 삼각형
  prism(r, [, light, point]) {
    return (
      <>
        {Array.from({ length: 9 }, (_, i) => {
          const x = r() * 300;
          const y = 40 + r() * 300;
          const s = 60 + r() * 110;
          return (
            <polygon key={i} points={`${x},${y - s * 0.6} ${x + s * 0.55},${y + s * 0.4} ${x - s * 0.55},${y + s * 0.4}`}
              fill={[light, point, '#fff'][i % 3]} opacity={0.16 + r() * 0.3} transform={`rotate(${r() * 70 - 35} ${x} ${y})`} />
          );
        })}
        <line x1="-10" y1={230 + r() * 40} x2="310" y2={120 + r() * 40} stroke="#fff" strokeWidth="1.2" opacity="0.7" />
      </>
    );
  },
};

/**
 * 사진 없이 그리는 공연 포스터 — 장르마다 색과 무늬가 다르고, 같은 공연은 늘 같은 그림이 나온다.
 * concert(화면용 정보가 붙은 공연) 또는 title만 줘도 된다. bare면 글자 없이 그림만.
 */
export default function Poster({ concert, title, venue, size = 'md', bare = false, children }) {
  const c = useMemo(
    () => (concert && concert.palette ? concert : enrich(concert || { title, venue })),
    [concert, title, venue],
  );
  const [dark, light] = c.palette;
  const art = useMemo(() => (ART[c.art] || ART.orbit)(seeded(c.title), c.palette), [c.art, c.title, c.palette]);
  const headline = c.artist || c.title || '';
  const sub = c.artist && c.title !== c.artist ? c.title.replace(c.artist, '').replace(/^[\s\-–·]+|[\s\-–·]+$/g, '') : '';

  return (
    <div className={`poster poster-${size}`} style={{ background: `linear-gradient(155deg, ${light} 0%, ${dark} 78%)` }}>
      <svg className="poster-art" viewBox="0 0 300 400" preserveAspectRatio="xMidYMid slice" aria-hidden="true">{art}</svg>
      {!bare && (
        <div className="poster-text">
          <span className="poster-genre">{c.genreLabel}</span>
          <div className="poster-bottom">
            <strong className="poster-headline">{headline}</strong>
            {sub && <span className="poster-sub">{sub}</span>}
            {(c.eventStart || c.hall) && (
              <span className="poster-meta">{[c.eventStart && shortDate(c.eventStart), c.hall].filter(Boolean).join(' · ')}</span>
            )}
          </div>
        </div>
      )}
      {children}
    </div>
  );
}
