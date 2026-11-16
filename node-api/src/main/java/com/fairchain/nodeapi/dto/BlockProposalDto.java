package com.fairchain.nodeapi.dto;

/**
 * 노드 사이에 주고받는 확정 블록 (따라잡기 · 팀원 전송 · 복구). 받는 노드가 blockHash를 다시 계산해 검증한다.
 *
 * @param timestamp epoch 밀리초 — 기록만 하고 블록 해시에는 넣지 않는다
 * @param signerId  이 블록에 서명한 리더 층 노드 ID (서명이 없으면 null)
 * @param signature signerId의 개인키로 blockHash에 한 서명 (Base64). 블록 해시 계산에는 들어가지 않는다
 */
public record BlockProposalDto(
        Long blockIndex,
        String prevBlockHash,
        String recordKey,
        Long version,
        String recordHash,
        Long timestamp,
        String blockHash,
        String signerId,
        String signature
) {

    /** 서명 없는 블록 */
    public BlockProposalDto(Long blockIndex, String prevBlockHash, String recordKey, Long version, String recordHash,
                            Long timestamp, String blockHash) {
        this(blockIndex, prevBlockHash, recordKey, version, recordHash, timestamp, blockHash, null, null);
    }
}
