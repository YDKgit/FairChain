// 서버 enum · 값 → 화면 글자 (한 곳에서만 정한다)

export const ticketStatusText = { ACTIVE: '사용 가능', CANCELLED: '취소됨', CHECKED_IN: '입장 완료' };

export const offerStatusText = { WAITING: '차례 대기', OFFERED: '제안 중', ACCEPTED: '예매 완료', EXPIRED: '기한 지남', SKIPPED: '차례 안 옴' };

export const roundStatusText = { OFFERING: '명단에 제안 중', RELEASE_PENDING: '명단 소진 — 일반 판매 대기', DONE: '종료' };

/** 티켓과 블록체인 기록을 대조한 결과 → [색, 짧은 글, 풀어 쓴 설명] */
export const verificationBadge = {
  VERIFIED: ['ok', '블록체인 검증 완료', '노드에 남은 기록과 이 티켓이 일치합니다. 입장할 수 있습니다.'],
  PENDING: ['wait', '블록 기록 중', '노드들이 이 티켓의 기록을 확정하는 중입니다. 몇 초 뒤 검증 완료로 바뀝니다.'],
  TAMPER_SUSPECTED: ['bad', '위조 의심', '노드에 남은 기록과 이 티켓의 내용이 다릅니다. 입장이 거절됩니다.'],
  UNREGISTERED_LOCAL: ['bad', '기록 없음 (가짜 의심)', '블록체인에 이 티켓의 기록이 없습니다. 입장이 거절됩니다.'],
  MISSING_LOCAL: ['warn', '기록만 있음 (삭제 의심)', '블록체인에는 내 티켓으로 남아 있는데 예매 사이트에는 없습니다.'],
  UNAVAILABLE: ['muted', '노드 확인 불가', '지금은 노드에 물어볼 수 없어 대조하지 못했습니다.'],
};

export const saleBadge = {
  open: ['ok', '예매중'],
  upcoming: ['wait', '오픈예정'],
  ready: ['muted', '좌석 준비중'],
  soldout: ['bad', '매진'],
  ended: ['muted', '공연종료'],
};

/** "20:00:00" → "20:00" */
export const hhmm = (t) => (t ? String(t).slice(0, 5) : '-');

/** ISO-8601 기간(PT10M, PT24H) → "10분", "24시간" */
export function durationText(iso) {
  const m = /PT(?:(\d+)H)?(?:(\d+)M)?/.exec(iso || '');
  if (!m) return iso;
  return [m[1] && `${m[1]}시간`, m[2] && `${m[2]}분`].filter(Boolean).join(' ');
}
