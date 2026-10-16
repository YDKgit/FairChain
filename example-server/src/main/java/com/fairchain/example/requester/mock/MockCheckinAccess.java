package com.fairchain.example.requester.mock;

import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyRequestDto;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.example.entity.Ticket;
import com.fairchain.example.requester.CheckinAccess;
import com.fairchain.example.requester.RecordFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** fairchain-mock 체크인 */
@Component
@Profile("fairchain-mock")
public class MockCheckinAccess implements CheckinAccess {

    private final InMemoryFairChainStub stub;
    private final RecordFactory recordFactory;

    public MockCheckinAccess(InMemoryFairChainStub stub, RecordFactory recordFactory) {
        this.stub = stub;
        this.recordFactory = recordFactory;
    }

    @Override
    public VerifyResultDto verify(Ticket ticket) {
        return stub.verify(new VerifyRequestDto(
                recordFactory.recordKey(ticket.getSeatId()), ticket.getVersion(), recordFactory.recordHash(ticket)));
    }

    @Override
    public void recordCheckin(Ticket ticket) {
        stub.registerRecord(buildRecord(ticket));
    }

    @Override
    public RecordRequestDto buildRecord(Ticket ticket) {
        return recordFactory.build(ticket, RecordType.CHECKIN);
    }
}
