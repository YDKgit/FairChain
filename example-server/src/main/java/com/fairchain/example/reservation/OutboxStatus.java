package com.fairchain.example.reservation;

public enum OutboxStatus {
    /** 전송 대기 (FairChain이 응답하지 않으면 계속 재전송) */
    PENDING,
    /** 전송 완료 */
    SENT,
    /** FairChain이 거절함 (다시 보내도 같은 결과) */
    FAILED
}
