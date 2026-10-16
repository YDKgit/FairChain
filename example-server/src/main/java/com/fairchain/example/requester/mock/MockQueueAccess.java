package com.fairchain.example.requester.mock;

import com.fairchain.api.dto.EnterQueueResponseDto;
import com.fairchain.api.dto.QueueStatusResponseDto;
import com.fairchain.example.requester.QueueAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/** fairchain-mock 대기열 */
@Component
@Profile("fairchain-mock")
public class MockQueueAccess implements QueueAccess {

    private final InMemoryFairChainStub stub;

    public MockQueueAccess(InMemoryFairChainStub stub) {
        this.stub = stub;
    }

    @Override
    public EnterQueueResponseDto enter(Long memberId, Long eventId) {
        return stub.enter(memberId, eventId);
    }

    @Override
    public void leave(Long memberId, Long eventId) {
        stub.leave(memberId, eventId);
    }

    @Override
    public void leaveAll(Long memberId) {
        stub.leaveAll(memberId);
    }

    @Override
    public void subscribe(Long memberId, Long eventId, Consumer<QueueStatusResponseDto> onEvent) {
        stub.subscribe(memberId, eventId, onEvent);
    }
}
