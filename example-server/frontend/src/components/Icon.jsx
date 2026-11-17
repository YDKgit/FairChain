/** 선으로 그린 작은 아이콘 (24×24). name으로 고른다 */
const PATHS = {
  search: <><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></>,
  ticket: <><path d="M3 9a2 2 0 0 0 0 6v3a1 1 0 0 0 1 1h16a1 1 0 0 0 1-1v-3a2 2 0 0 1 0-6V6a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1z" /><path d="M14 5v3m0 3v2m0 3v3" /></>,
  bell: <><path d="M6 9a6 6 0 1 1 12 0c0 6 2.5 7 2.5 8h-17C3.5 16 6 15 6 9z" /><path d="M10 20a2 2 0 0 0 4 0" /></>,
  user: <><circle cx="12" cy="8" r="4" /><path d="M4 21c0-4 3.6-6.5 8-6.5s8 2.500 8 6.500" /></>,
  pin: <><path d="M12 21s7-6.2 7-11.500A7 7 0 0 0 5 9.500C5 14.800 12 21 12 21z" /><circle cx="12" cy="9.500" r="2.500" /></>,
  calendar: <><rect x="3.500" y="5" width="17" height="15.500" rx="2" /><path d="M3.500 10h17M8 3v4m8-4v4" /></>,
  clock: <><circle cx="12" cy="12" r="8.500" /><path d="M12 7.500V12l3 2" /></>,
  shield: <><path d="M12 3 4.500 6v5.500c0 4.700 3.200 8.100 7.500 9.500 4.300-1.400 7.500-4.800 7.500-9.500V6z" /><path d="m8.800 12 2.300 2.300 4.200-4.600" /></>,
  queue: <><circle cx="6" cy="8" r="2.500" /><circle cx="12" cy="8" r="2.500" /><circle cx="18" cy="8" r="2.500" /><path d="M2.500 19c0-2.600 1.600-4.200 3.500-4.200S9.500 16.400 9.500 19m-1 0c0-2.600 1.600-4.200 3.500-4.200s3.500 1.600 3.500 4.200m-1 0c0-2.600 1.600-4.200 3.500-4.200s3.500 1.600 3.500 4.200" /></>,
  chain: <><rect x="3" y="8.500" width="8" height="7" rx="3.500" /><rect x="13" y="8.500" width="8" height="7" rx="3.500" /><path d="M9 12h6" /></>,
  swap: <><path d="M4 8h14l-3.500-3.500M20 16H6l3.500 3.500" /></>,
  qr: <><rect x="3.500" y="3.500" width="6.500" height="6.500" rx="1" /><rect x="14" y="3.500" width="6.500" height="6.500" rx="1" /><rect x="3.500" y="14" width="6.500" height="6.500" rx="1" /><path d="M14 14h2.500v2.500H14zM18 18h2.500v2.500H18zM18 14h2.500M14 18v2.500" /></>,
  left: <path d="m14.500 5-7 7 7 7" />,
  right: <path d="m9.500 5 7 7-7 7" />,
  check: <path d="m5 12.500 4.500 4.500L19 7.500" />,
  close: <path d="M6 6l12 12M18 6 6 18" />,
  copy: <><rect x="8.500" y="8.500" width="11.500" height="11.500" rx="2" /><path d="M5.500 15.500h-1a1 1 0 0 1-1-1v-10a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1" /></>,
  info: <><circle cx="12" cy="12" r="8.500" /><path d="M12 11v5.500M12 7.600v.3" /></>,
};

export default function Icon({ name, size = 20, stroke = 1.8, className = '' }) {
  return (
    <svg className={`icon ${className}`} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {PATHS[name] || null}
    </svg>
  );
}
