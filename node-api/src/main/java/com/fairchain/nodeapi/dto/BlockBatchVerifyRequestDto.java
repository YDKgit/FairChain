package com.fairchain.nodeapi.dto;

import java.util.List;

public record BlockBatchVerifyRequestDto(List<BlockVerifyItemDto> records) {}
