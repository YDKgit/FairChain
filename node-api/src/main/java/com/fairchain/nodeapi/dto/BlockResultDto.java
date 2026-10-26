package com.fairchain.nodeapi.dto;

/**
 * @param blockIndex 확정된 블록 번호 (accepted가 false면 null)
 * @param blockHash  그 블록의 해시 — 이전 블록 해시가 들어가므로 같으면 그 번호까지의 체인 전체가 같다 (accepted가 false면 null)
 */
public record BlockResultDto(boolean accepted, Long blockIndex, String blockHash) {}
