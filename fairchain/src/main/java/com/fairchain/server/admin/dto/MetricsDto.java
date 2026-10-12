package com.fairchain.server.admin.dto;

/** 관제 대시보드 공연별 지표 (SD-605) */
public record MetricsDto(Long eventId, long waitingCount, long blockedCount, long remainingSlots) {}
