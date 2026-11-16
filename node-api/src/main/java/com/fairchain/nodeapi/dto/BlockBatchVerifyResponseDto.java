package com.fairchain.nodeapi.dto;

import java.util.List;

/** @param lastBlockIndex 이 노드의 마지막 블록 번호 — 뒤처짐과 변조를 구분하는 데 쓴다 (미정사항 13번) */
public record BlockBatchVerifyResponseDto(List<BlockVerifyItemResultDto> results, Long lastBlockIndex) {

    /** 예전 형식 (마지막 블록 번호 없음) — FairChain 코드 호환용 */
    public BlockBatchVerifyResponseDto(List<BlockVerifyItemResultDto> results) {
        this(results, null);
    }
}
