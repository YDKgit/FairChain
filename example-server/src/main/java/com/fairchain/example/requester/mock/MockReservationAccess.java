package com.fairchain.example.requester.mock;

import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.example.entity.Ticket;
import com.fairchain.example.requester.RecordFactory;
import com.fairchain.example.requester.ReservationAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** fairchain-mock 예매 — 지문은 실제와 같은 RecordFactory로 계산 */
@Component
@Profile("fairchain-mock")
public class MockReservationAccess implements ReservationAccess {

    private final InMemoryFairChainStub stub;
    private final RecordFactory recordFactory;

    public MockReservationAccess(InMemoryFairChainStub stub, RecordFactory recordFactory) {
        this.stub = stub;
        this.recordFactory = recordFactory;
    }

    @Override
    public VerifyResultDto verifyAdmission(Long userId, Long eventId) {
        return stub.verifyAdmission(userId, eventId);
    }

    @Override
    public RecordResponseDto registerRecord(Ticket ticket, RecordType type) {
        return registerRecord(buildRecord(ticket, type));
    }

    @Override
    public RecordRequestDto buildRecord(Ticket ticket, RecordType type) {
        return recordFactory.build(ticket, type);
    }

    @Override
    public RecordResponseDto registerRecord(RecordRequestDto dto) {
        return stub.registerRecord(dto);
    }

    @Override
    public ReallocationListDto getReallocationCandidates(Long eventId) {
        return stub.getReallocationCandidates(eventId);
    }
}
