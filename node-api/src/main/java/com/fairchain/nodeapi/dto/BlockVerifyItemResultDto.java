package com.fairchain.nodeapi.dto;

/**
 * @param matched   블록의 지문이 요청과 같으면 true
 * @param confirmed 블록 해시 · 이전 블록 연결까지 정상이면 true
 */
public record BlockVerifyItemResultDto(Long blockIndex, boolean matched, boolean confirmed) {}
