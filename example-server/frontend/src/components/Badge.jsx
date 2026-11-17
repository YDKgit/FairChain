import { saleBadge, verificationBadge } from '../labels.js';
import Icon from './Icon.jsx';

export { ticketStatusText } from '../labels.js';

/** 티켓을 블록체인 기록과 대조한 결과 */
export function VerifyBadge({ status }) {
  if (!status) return null;
  const [kind, text] = verificationBadge[status] || ['muted', status];
  return (
    <span className={`badge ${kind}`}>
      {kind === 'ok' && <Icon name="shield" size={13} stroke={2.2} />}
      {kind === 'wait' && <span className="spinner" />}
      {text}
    </span>
  );
}

/** 공연 판매 상태 */
export function SaleBadge({ state, solid = false }) {
  const [kind, text] = saleBadge[state] || ['muted', ''];
  return text ? <span className={`badge ${kind} ${solid ? 'solid' : ''}`}>{text}</span> : null;
}
