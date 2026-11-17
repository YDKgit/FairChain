package com.fairchain.example.reservation;

public enum OfferStatus {
    /** 차례 대기 */
    WAITING,
    /** 제안 중, 응답 대기 */
    OFFERED,
    /** 재배정 예매 완료 */
    ACCEPTED,
    /** 기한 초과 */
    EXPIRED,
    /** 회차가 끝나 차례가 오지 않음 (취소표가 먼저 다 나감 · 일반 판매로 풀림) */
    SKIPPED
}
