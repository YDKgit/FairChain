package com.fairchain.example.entity;

/** C-0321 재배정 제안 상태 */
public enum OfferStatus {
    /** 알림 대기 */
    WAITING,
    /** 알림 발송, 응답 대기 */
    OFFERED,
    ACCEPTED,
    EXPIRED
}
