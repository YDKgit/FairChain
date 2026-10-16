package com.fairchain.example.requester;

import com.fairchain.api.TicketVerificationApi;
import com.fairchain.api.dto.AnchorListRequestDto;
import com.fairchain.api.dto.AnchorListResponseDto;
import com.fairchain.api.http.HttpRequester;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** C-0511 티켓 목록 검증 Requester */
@Component
@Profile("!fairchain-mock")
public class TicketVerificationRequester implements TicketVerificationAccess {

    private final HttpRequester httpRequester;

    public TicketVerificationRequester(HttpRequester httpRequester) {
        this.httpRequester = httpRequester;
    }

    @Override
    public AnchorListResponseDto getVerifiedAnchors(AnchorListRequestDto dto) {
        return FairChainCall.call("티켓 기록 조회", () -> httpRequester.post(
                TicketVerificationApi.ANCHORS, dto, AnchorListResponseDto.class));
    }
}
