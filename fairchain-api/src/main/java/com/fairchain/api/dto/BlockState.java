package com.fairchain.api.dto;

/** 블록 기록 상태 — PENDING이면 노드 대조를 생략하고 "확인 중"으로 표시 */
public enum BlockState {
    PENDING,
    CONFIRMED
}
