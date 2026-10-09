package com.fairchain.api.dto;

import java.util.List;

/** 좌석 등록 — 좌석마다 ISSUE 기록을 한 번에 요청 */
public record SeatIssueRequestDto(Long eventId, List<RecordRequestDto> records) {}
