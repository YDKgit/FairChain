package com.fairchain.nodeapi.dto;

import java.time.LocalDateTime;

/**
 * 노드에 저장된 블록 그대로 (조회 · 점검용)
 *
 * @param signerId  서명한 리더 층 노드 ID (서명이 없으면 null)
 * @param signature blockHash에 한 서명 (Base64)
 */
public record BlockDto(
        Long blockIndex,
        String prevBlockHash,
        String recordKey,
        Long version,
        String recordHash,
        LocalDateTime timestamp,
        String blockHash,
        String signerId,
        String signature
) {

    /** 서명 없는 블록 — 예전 형식 */
    public BlockDto(Long blockIndex, String prevBlockHash, String recordKey, Long version, String recordHash,
                    LocalDateTime timestamp, String blockHash) {
        this(blockIndex, prevBlockHash, recordKey, version, recordHash, timestamp, blockHash, null, null);
    }
}
