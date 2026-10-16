package com.fairchain.example.requester;

import com.fairchain.api.dto.EnterQueueResponseDto;
import com.fairchain.api.dto.QueueStatusResponseDto;

import java.util.function.Consumer;

/** C-0204 대기열 FairChain 접근 */
public interface QueueAccess {

    EnterQueueResponseDto enter(Long memberId, Long eventId);

    void leave(Long memberId, Long eventId);

    void leaveAll(Long memberId);

    /** 실시간 순번 구독 — 비동기, 이벤트마다 onEvent 호출 */
    void subscribe(Long memberId, Long eventId, Consumer<QueueStatusResponseDto> onEvent);
}
