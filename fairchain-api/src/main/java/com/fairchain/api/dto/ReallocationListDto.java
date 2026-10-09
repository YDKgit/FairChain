package com.fairchain.api.dto;

import java.util.List;

/** @param userIds 원래 대기 순번순 재배정 명단 */
public record ReallocationListDto(Long eventId, List<Long> userIds) {}
