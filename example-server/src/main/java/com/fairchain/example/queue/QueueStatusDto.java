package com.fairchain.example.queue;

import com.fairchain.api.dto.QueueStatusResponseDto;

/**
 * @param state        WAITING / ADMITTED / EXPIRED / NOT_FOUND
 * @param rank         현재 대기 순위 (WAITING일 때)
 * @param waitingCount 전체 대기 인원
 * @param nextPollMs   WAITING일 때 화면이 다음에 상태를 물을 때까지 기다릴 시간(ms) — 앞쪽일수록 짧다. 아니면 null
 */
public record QueueStatusDto(String state, Long rank, Long waitingCount, Long nextPollMs) {

    static QueueStatusDto of(QueueStatusResponseDto dto) {
        return new QueueStatusDto(dto.state(), dto.rank(), dto.waitingCount(), dto.nextPollMs());
    }
}
