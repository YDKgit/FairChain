import { useEffect, useState } from 'react';
import { remaining } from '../utils.js';

/** 기한까지 남은 시간 (mm:ss) — 끝나면 onExpire */
export default function Countdown({ until, onExpire }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  useEffect(() => {
    if (until && new Date(until).getTime() <= now && onExpire) onExpire();
  }, [now, until, onExpire]);
  return <span className="countdown">{remaining(until, now)}</span>;
}
