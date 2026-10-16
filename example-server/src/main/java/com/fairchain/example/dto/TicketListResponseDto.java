package com.fairchain.example.dto;

import java.util.List;

/** C-0504 내 티켓 목록 */
public record TicketListResponseDto(List<TicketDetailResponseDto> tickets) {}
