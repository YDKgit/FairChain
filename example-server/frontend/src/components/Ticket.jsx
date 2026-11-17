import { useEffect, useRef } from 'react';
import QRCode from 'qrcode';
import { useAuth } from '../auth.jsx';
import { ticketStatusText, verificationBadge } from '../labels.js';
import { clock, day, splitSeat, when, won } from '../utils.js';
import { VerifyBadge } from './Badge.jsx';
import Icon from './Icon.jsx';
import Poster from './Poster.jsx';

const DANGER = new Set(['TAMPER_SUSPECTED', 'UNREGISTERED_LOCAL']);

/** 입장용 QR 그림 — 게이트에서 이 토큰을 찍으면 FairChain 기록과 대조한다 */
export function Qr({ value, size = 180 }) {
  const canvas = useRef(null);
  useEffect(() => {
    if (canvas.current && value) QRCode.toCanvas(canvas.current, value, { width: size, margin: 1 }).catch(() => {});
  }, [value, size]);
  return <canvas ref={canvas} className="qr" width={size} height={size} />;
}

/** 좌석을 등급 · 열 · 번호 칸으로 나눠 보여 준다 */
function SeatBlocks({ ticket }) {
  const { row, num } = splitSeat(ticket.seatNumber || '');
  return (
    <div className="seatblocks">
      <div><span>등급</span><b>{ticket.seatGrade}석</b></div>
      <div><span>열</span><b>{row}</b></div>
      <div><span>번호</span><b>{num}</b></div>
    </div>
  );
}

/**
 * 내 티켓 목록의 티켓 한 장 — 왼쪽 포스터 · 가운데 공연과 좌석 · 오른쪽 절취선 너머 QR.
 * concert는 화면용 정보가 붙은 공연 (없어도 제목만으로 그린다)
 */
export function TicketCard({ ticket: t, concert, onOpen, onCancel }) {
  const danger = DANGER.has(t.verificationStatus);
  const used = t.status === 'CHECKED_IN';
  const local = !!t.ticketId;
  return (
    <article className={`tk ${danger ? 'danger' : ''} ${used ? 'used' : ''}`}>
      <div className="tk-poster"><Poster concert={concert} title={t.concertTitle} bare /></div>
      <div className="tk-main">
        <div className="tk-badges">
          <VerifyBadge status={t.verificationStatus} />
          <span className={`badge ${used ? 'ok' : 'muted'}`}>{ticketStatusText[t.status] || t.status}</span>
        </div>
        <h3 className="tk-title">{t.concertTitle}</h3>
        {concert && (
          <p className="tk-when"><Icon name="calendar" size={15} /> {when(concert.eventStart)} <span className="sep" /> <Icon name="pin" size={15} /> {concert.venue}</p>
        )}
        {local ? (
          <>
            <SeatBlocks ticket={t} />
            <p className="tk-meta">
              예매번호 T{String(t.ticketId).padStart(6, '0')} · {won(t.price)} · 예매 {when(t.issuedAt)}
              {t.checkedInAt && <> · 입장 {when(t.checkedInAt)}</>}
            </p>
          </>
        ) : (
          <p className="tk-meta">예매 사이트에는 없는데 블록체인에는 내 티켓으로 남아 있는 기록입니다 (기록 v{t.version}{t.blockIndex != null && `, 블록 #${t.blockIndex}`}).</p>
        )}
      </div>
      <div className="tk-stub">
        {local && t.status === 'ACTIVE' ? (
          <>
            <button className="tk-qr" onClick={() => onOpen(t)} aria-label="모바일 티켓 열기"><Qr value={t.qrToken} size={92} /></button>
            <button className="btn small full" onClick={() => onOpen(t)}>모바일 티켓</button>
            <button className="linkbtn" onClick={() => onCancel(t)}>예매 취소</button>
          </>
        ) : used ? (
          <div className="tk-stamp"><Icon name="check" size={30} stroke={2.6} /><span>입장 완료</span></div>
        ) : (
          <div className="tk-stamp warn"><Icon name="info" size={28} /><span>확인 필요</span></div>
        )}
        {local && <span className="tk-block mono">v{t.version}{t.blockIndex != null && ` · #${t.blockIndex}`}</span>}
      </div>
    </article>
  );
}

/** 모바일 티켓 — 입장할 때 보여 주는 화면. 블록체인 대조 결과가 함께 나온다 */
export function TicketModal({ ticket: t, concert, onClose }) {
  const { toast } = useAuth();
  useEffect(() => {
    if (!t) return undefined;
    const onKey = (e) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [t, onClose]);
  if (!t) return null;

  const [kind, , explain] = verificationBadge[t.verificationStatus] || ['muted', '', ''];
  const copy = async () => {
    try { await navigator.clipboard.writeText(t.qrToken); toast('QR 토큰을 복사했습니다 — 게이트 화면에 붙여 넣으세요'); }
    catch { toast('복사하지 못했습니다', 'error'); }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="mticket" onClick={(e) => e.stopPropagation()}>
        <button className="mticket-close" onClick={onClose} aria-label="닫기"><Icon name="close" /></button>
        <div className="mticket-head">
          <Poster concert={concert} title={t.concertTitle} bare />
          <div className="mticket-headtext">
            <span className="tag light">{concert?.genreLabel || '콘서트'}</span>
            <h3>{t.concertTitle}</h3>
          </div>
        </div>
        <div className="mticket-body">
          {concert && (
            <dl className="mticket-info">
              <div><dt>일시</dt><dd>{day(concert.eventStart)} {clock(concert.eventStart)}</dd></div>
              <div><dt>장소</dt><dd>{concert.venue}</dd></div>
            </dl>
          )}
          <SeatBlocks ticket={t} />
        </div>
        <div className="mticket-cut" />
        <div className="mticket-qr">
          <Qr value={t.qrToken} size={196} />
          <div className={`verify-box ${kind}`}>
            <VerifyBadge status={t.verificationStatus} />
            <p>{explain}</p>
            <span className="mono">기록 v{t.version}{t.blockIndex != null ? ` · 블록 #${t.blockIndex}` : ' · 블록 확정 전'}</span>
          </div>
          <code className="token">{t.qrToken}</code>
          <button className="btn ghost small" onClick={copy}><Icon name="copy" size={15} /> 토큰 복사 (게이트 시연용)</button>
        </div>
      </div>
    </div>
  );
}
