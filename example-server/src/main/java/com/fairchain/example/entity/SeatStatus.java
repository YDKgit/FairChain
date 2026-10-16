package com.fairchain.example.entity;

/** C-0319 좌석 상태 */
public enum SeatStatus {
    /** 예매 가능 */
    EMPTY,
    /** 예매됨 */
    RESERVED,
    /** 취소 후 재배정 중 — 일반 예매 차단 */
    CANCELLED
}
