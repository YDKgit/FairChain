package com.fairchain.example.ticket;

import com.fairchain.api.TicketVerificationApi;
import com.fairchain.api.dto.AnchorListRequestDto;
import com.fairchain.api.dto.AnchorListResponseDto;
import com.fairchain.api.http.HttpRequester;
import org.springframework.stereotype.Component;

/** /api/tickets/anchors로 POST 전송 (C-0511) */
@Component
public class TicketVerificationRequester implements TicketVerificationAccess {

    private final HttpRequester httpRequester;

    public TicketVerificationRequester(HttpRequester httpRequester) {
        this.httpRequester = httpRequester;
    }

    @Override
    public AnchorListResponseDto getVerifiedAnchors(AnchorListRequestDto dto) {
        return httpRequester.post(TicketVerificationApi.ANCHORS, dto, AnchorListResponseDto.class);
    }
}
