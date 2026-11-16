package com.fairchain.nodeapi.dto;

/**
 * 복귀 요청 결과.
 *
 * @param logIndex 복귀가 기록된 Raft 로그 번호 — 리더 층 노드는 이 번호 앞의 "제외" 기록을 다시 적용할 때 무시한다
 */
public record RestoreResultDto(boolean accepted, String message, Long logIndex) {}
