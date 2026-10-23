package com.fairchain.example.queue;

import com.fairchain.api.dto.QueueStatusResponseDto;

/**
 * @param state        WAITING / ADMITTED / EXPIRED / NOT_FOUND
 * @param rank         현재 대기 순위 (WAITING일 때)
 * @param waitingCount 전체 대기 인원
 */
public record QueueStatusDto(String state, Long rank, Long waitingCount) {

    static QueueStatusDto of(QueueStatusResponseDto dto) {
        return new QueueStatusDto(dto.state(), dto.rank(), dto.waitingCount());
    }
}
