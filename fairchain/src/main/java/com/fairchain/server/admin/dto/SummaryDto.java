package com.fairchain.server.admin.dto;

/**
 * 관제 대시보드 전체 요약 (고객사 단위).
 *
 * @param admittedCount  대기열을 통과해 입장한 횟수
 * @param confirmedCount 블록에 확정된 기록 수 (기록 1건 = 블록 1개)
 * @param pendingCount   블록 확정을 기다리는 기록 수
 */
public record SummaryDto(long admittedCount, long confirmedCount, long pendingCount) {}
