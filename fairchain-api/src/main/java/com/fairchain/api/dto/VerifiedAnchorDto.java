package com.fairchain.api.dto;

/** @param nodeMatched 노드 3대 중 2/3 이상이 같은 지문을 가지고 있으면 true */
public record VerifiedAnchorDto(
        String recordKey,
        Long version,
        String recordHash,
        Long blockIndex,
        BlockState blockState,
        boolean nodeMatched
) {}
