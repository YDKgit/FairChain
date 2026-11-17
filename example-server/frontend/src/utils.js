// 화면 공용 포맷 함수

export const won = (v) => (v == null ? '-' : `${Number(v).toLocaleString('ko-KR')}원`);

const pad = (n) => String(n).padStart(2, '0');
const DAYS = ['일', '월', '화', '수', '목', '금', '토'];

export function parseDate(t) {
  if (!t) return null;
  const d = new Date(t);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** 2026.11.06 (금) 19:00 */
export function when(t) {
  const d = parseDate(t);
  if (!d) return '-';
  return `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())} (${DAYS[d.getDay()]}) ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 2026.11.06 (금) */
export function day(t) {
  const d = parseDate(t);
  if (!d) return '-';
  return `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())} (${DAYS[d.getDay()]})`;
}

/** 11.06 (금) */
export function shortDate(t) {
  const d = parseDate(t);
  if (!d) return '-';
  return `${pad(d.getMonth() + 1)}.${pad(d.getDate())} (${DAYS[d.getDay()]})`;
}

/** 19:00 */
export function clock(t) {
  const d = parseDate(t);
  return d ? `${pad(d.getHours())}:${pad(d.getMinutes())}` : '-';
}

/** 날짜 조각 — 달력 모양 표시용 { month: "11", date: "06", weekday: "금" } */
export function dateParts(t) {
  const d = parseDate(t);
  if (!d) return { month: '-', date: '-', weekday: '' };
  return { month: pad(d.getMonth() + 1), date: pad(d.getDate()), weekday: DAYS[d.getDay()] };
}

/** 오늘부터 그날까지 남은 날 수 (지났으면 음수) */
export function daysUntil(t) {
  const d = parseDate(t);
  if (!d) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const target = new Date(d);
  target.setHours(0, 0, 0, 0);
  return Math.round((target - today) / 86400000);
}

/** D-3 · D-DAY · D+2 */
export function dday(t) {
  const diff = daysUntil(t);
  if (diff == null) return '';
  if (diff === 0) return 'D-DAY';
  return diff < 0 ? `D+${-diff}` : `D-${diff}`;
}

/** 남은 시간을 mm:ss 로 */
export function remaining(until, now = Date.now()) {
  const d = parseDate(until);
  if (!d) return '';
  const sec = Math.max(0, Math.floor((d.getTime() - now) / 1000));
  return `${pad(Math.floor(sec / 60))}:${pad(sec % 60)}`;
}

/** 150 → "2시간 30분" */
export function minutesText(min) {
  if (!min) return '-';
  const h = Math.floor(min / 60);
  const m = min % 60;
  return [h && `${h}시간`, m && `${m}분`].filter(Boolean).join(' ');
}

/** "A12" → { row: "A", num: 12 } */
export function splitSeat(seatNumber = '') {
  const m = /^([A-Za-z가-힣]*)\s*(\d+)$/.exec(seatNumber.trim());
  return m ? { row: m[1] || '-', num: Number(m[2]) } : { row: '-', num: seatNumber };
}

/**
 * 공연 판매 상태.
 * ended 공연 종료 · upcoming 예매 오픈 전 · ready 오픈했지만 좌석 미등록 · soldout 매진 · open 예매중
 */
export function saleState(concert, now = new Date()) {
  if (!concert) return 'unknown';
  const open = parseDate(concert.eventOpen);
  const end = parseDate(concert.eventEnd);
  if (end && end < now) return 'ended';
  if (open && open > now) return 'upcoming';
  if (!concert.totalSeats) return 'ready';
  if (concert.emptySeats === 0) return 'soldout';
  return 'open';
}

/** 예매율(%) — 좌석이 없으면 0 */
export function bookedRate(concert) {
  if (!concert?.totalSeats) return 0;
  return Math.round(((concert.totalSeats - concert.emptySeats) / concert.totalSeats) * 100);
}
