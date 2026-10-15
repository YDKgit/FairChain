package com.fairchain.example.reservation;

public enum OfferStatus {
    /** 알림 대기 */
    WAITING,
    /** 알림 발송, 응답 대기 */
    OFFERED,
    /** 재배정 예매 완료 */
    ACCEPTED,
    /** 기한 초과 */
    EXPIRED
}
