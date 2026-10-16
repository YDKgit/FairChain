package com.fairchain.example.requester.mock;

import com.fairchain.api.dto.ApiKeyDto;
import com.fairchain.example.entity.Seat;
import com.fairchain.example.requester.AdminAccess;
import com.fairchain.example.requester.RecordFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/** fairchain-mock 운영 관리 */
@Component
@Profile("fairchain-mock")
public class MockAdminAccess implements AdminAccess {

    private final InMemoryFairChainStub stub;
    private final RecordFactory recordFactory;

    public MockAdminAccess(InMemoryFairChainStub stub, RecordFactory recordFactory) {
        this.stub = stub;
        this.recordFactory = recordFactory;
    }

    @Override
    public ApiKeyDto getKeys() {
        return stub.getKeys();
    }

    @Override
    public void issueSeats(Long concertId, List<Seat> seats) {
        seats.forEach(seat -> stub.registerRecord(recordFactory.buildIssue(concertId, seat)));
    }
}
