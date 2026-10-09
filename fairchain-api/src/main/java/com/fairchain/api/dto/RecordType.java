package com.fairchain.api.dto;

/** 기록 종류 — 좌석 하나의 일생 (발행 → 판매 → 취소 → 재판매 → 입장) */
public enum RecordType {
    ISSUE,
    PURCHASE,
    CANCEL,
    CHECKIN
}
