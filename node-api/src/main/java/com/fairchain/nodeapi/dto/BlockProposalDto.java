package com.fairchain.nodeapi.dto;

/**
 * 다른 노드에 보내는 제안 블록. 받는 노드가 blockHash를 다시 계산해 검증한다.
 *
 * @param timestamp epoch 밀리초 — 블록 해시 계산에 들어가므로 제안 노드의 값을 그대로 전달
 */
public record BlockProposalDto(
        Long blockIndex,
        String prevBlockHash,
        String recordKey,
        Long version,
        String recordHash,
        Long timestamp,
        String blockHash
) {}
