package com.fairchain.example.requester.mock;

import com.fairchain.api.dto.AnchorListRequestDto;
import com.fairchain.api.dto.AnchorListResponseDto;
import com.fairchain.example.requester.TicketVerificationAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** fairchain-mock 티켓 목록 검증 */
@Component
@Profile("fairchain-mock")
public class MockTicketVerificationAccess implements TicketVerificationAccess {

    private final InMemoryFairChainStub stub;

    public MockTicketVerificationAccess(InMemoryFairChainStub stub) {
        this.stub = stub;
    }

    @Override
    public AnchorListResponseDto getVerifiedAnchors(AnchorListRequestDto dto) {
        return stub.getVerifiedAnchors(dto);
    }
}
