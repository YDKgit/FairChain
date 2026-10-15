package com.fairchain.example.queue;

import com.fairchain.api.dto.EnterQueueResponseDto;
import com.fairchain.api.dto.QueueStatusResponseDto;

import java.util.function.Consumer;

/** FairChain 대기열 호출 (C-0204) */
public interface QueueAccess {

    EnterQueueResponseDto enter(Long memberId, Long eventId);

    QueueStatusResponseDto status(Long memberId, Long eventId);

    void leave(Long memberId, Long eventId);

    void leaveAll(Long memberId);

    void subscribe(Long memberId, Long eventId, Consumer<QueueStatusResponseDto> onEvent);
}
