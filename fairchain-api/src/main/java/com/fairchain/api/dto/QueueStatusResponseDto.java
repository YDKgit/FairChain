package com.fairchain.api.dto;

public record QueueStatusResponseDto(String state, Long rank, Long waitingCount) {}
