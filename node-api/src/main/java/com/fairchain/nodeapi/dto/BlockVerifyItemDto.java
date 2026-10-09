package com.fairchain.nodeapi.dto;

public record BlockVerifyItemDto(Long blockIndex, String recordKey, Long version, String recordHash) {}
