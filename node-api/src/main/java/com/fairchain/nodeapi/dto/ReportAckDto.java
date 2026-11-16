package com.fairchain.nodeapi.dto;

/**
 * 신고 처리 결과. 조치가 있으면 판정은 Raft 로그에 기록되고 {@code GET /node/admin/status}의 verdicts에도 보인다.
 *
 * @param accepted 리더가 신고를 받아 판정했으면 true
 * @param reason   판정: OK(차이 없음) · BEHIND(뒤처짐, 조치 없음) · DIVERGED · CHAIN_INVALID(제외) · NOT_LEADER(리더에게 못 넘김)
 * @param verdict  제외 · 기록 중단처럼 조치가 있을 때 그 판정 (없으면 null)
 */
public record ReportAckDto(boolean accepted, ReasonCode reason, VerdictDto verdict) {

    public ReportAckDto(boolean accepted, ReasonCode reason) {
        this(accepted, reason, null);
    }
}
