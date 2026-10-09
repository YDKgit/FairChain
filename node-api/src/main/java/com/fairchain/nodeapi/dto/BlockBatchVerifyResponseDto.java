package com.fairchain.nodeapi.dto;

import java.util.List;

public record BlockBatchVerifyResponseDto(List<BlockVerifyItemResultDto> results) {}
