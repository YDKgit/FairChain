package com.fairchain.api;

import com.fairchain.api.dto.AnchorListRequestDto;
import com.fairchain.api.dto.AnchorListResponseDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** SS-05 티켓 목록 검증 — VerifyController가 구현 */
public interface TicketVerificationApi {

    String ANCHORS = "/api/tickets/anchors";

    @PostMapping(ANCHORS)
    AnchorListResponseDto getVerifiedAnchors(@RequestBody AnchorListRequestDto dto);
}
