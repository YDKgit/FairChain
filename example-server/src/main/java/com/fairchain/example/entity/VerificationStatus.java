package com.fairchain.example.entity;

/** C-0512 티켓 목록 검증 결과 */
public enum VerificationStatus {
    /** FairChain 기록 · 노드와 일치 */
    VERIFIED,
    /** 버전 · 지문이 다르거나 노드가 일치하지 않음 */
    TAMPER_SUSPECTED,
    /** FairChain 기록은 있는데 예제서버에 티켓이 없음 */
    MISSING_LOCAL,
    /** 예제서버 티켓이 FairChain에 기록되지 않음 (가짜 의심) */
    UNREGISTERED_LOCAL,
    /** 기록 전송 전이거나 블록 확정 전 */
    PENDING,
    /** 노드 검증 불가 (FairChain 사본 기준) */
    UNAVAILABLE
}
