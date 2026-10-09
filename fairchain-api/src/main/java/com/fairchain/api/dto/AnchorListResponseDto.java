package com.fairchain.api.dto;

import java.util.List;

/** @param verificationAvailable 노드가 응답하지 않아 사본 기준으로만 줬으면 false */
public record AnchorListResponseDto(List<VerifiedAnchorDto> anchors, boolean verificationAvailable) {}
