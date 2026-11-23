package com.fairchain.api.dto;

import java.util.List;

/**
 * @param userIds         원래 대기 순번순 재배정 명단
 * @param sequenceNumbers userIds와 같은 순서로, 그 사람이 대기열에 진입한 순번 (명단 화면에 함께 보여 주는 용도)
 */
public record ReallocationListDto(Long eventId, List<Long> userIds, List<Long> sequenceNumbers) {

    public ReallocationListDto(Long eventId, List<Long> userIds) {
        this(eventId, userIds, List.of());
    }
}
