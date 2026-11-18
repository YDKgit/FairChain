// 색 · 크기 — 웹 FairTicket과 같은 보라 계열
export const C = {
  bg: '#f4f4f7',
  card: '#ffffff',
  ink: '#16151d',
  sub: '#6b6a78',
  faint: '#a3a2b0',
  line: '#e6e5ee',
  primary: '#5b3df5',
  primarySoft: '#ede9ff',
  pink: '#db2777',
  ok: '#15803d',
  okSoft: '#dcfce7',
  bad: '#b91c1c',
  badSoft: '#fee2e2',
  warn: '#b45309',
  warnSoft: '#fef3c7',
  dark: '#17161f',
};

export const R = { card: 18, button: 14, chip: 999 };

export const badgeColors = {
  ok: [C.okSoft, C.ok],
  bad: [C.badSoft, C.bad],
  wait: [C.primarySoft, C.primary],
  warn: [C.warnSoft, C.warn],
  muted: ['#ececf2', C.sub],
};

export const gradeColor = (gradeName = '') => {
  const g = gradeName.toUpperCase();
  if (g === 'VIP') return '#f59e0b';
  if (g === 'R') return '#ec4899';
  if (g === 'S') return '#0ea5e9';
  return '#22c55e';
};
