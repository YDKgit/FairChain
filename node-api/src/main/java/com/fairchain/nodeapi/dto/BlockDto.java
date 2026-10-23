package com.fairchain.nodeapi.dto;

import java.time.LocalDateTime;

/** 노드에 저장된 블록 그대로 (조회 · 점검용) */
public record BlockDto(
        Long blockIndex,
        String prevBlockHash,
        String recordKey,
        Long version,
        String recordHash,
        LocalDateTime timestamp,
        String blockHash
) {}
