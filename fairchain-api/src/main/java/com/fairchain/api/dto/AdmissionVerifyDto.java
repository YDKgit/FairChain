package com.fairchain.api.dto;

/** 예매 전 입장 권한(대기열 통과) 확인 */
public record AdmissionVerifyDto(Long userId, Long eventId) {}
