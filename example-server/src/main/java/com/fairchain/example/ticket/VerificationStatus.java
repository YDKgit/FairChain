package com.fairchain.example.ticket;

/** 내 티켓과 FairChain · 노드 기록을 비교한 결과 (C-0512) */
public enum VerificationStatus {
    /** 노드와 일치 */
    VERIFIED,
    /** 버전 · 지문 불일치 (위조 의심) */
    TAMPER_SUSPECTED,
    /** 기록은 있으나 로컬 티켓 없음 (삭제 의심) */
    MISSING_LOCAL,
    /** 로컬 티켓만 있고 기록 없음 (가짜 의심) */
    UNREGISTERED_LOCAL,
    /** 블록 기록 중 */
    PENDING,
    /** 노드 확인 불가 */
    UNAVAILABLE
}
