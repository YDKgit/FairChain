package com.fairchain.example.ticket;

import com.fairchain.api.dto.AnchorListRequestDto;
import com.fairchain.api.dto.AnchorListResponseDto;

/** FairChain 티켓 목록 검증 호출 (C-0510) */
public interface TicketVerificationAccess {

    AnchorListResponseDto getVerifiedAnchors(AnchorListRequestDto dto);
}
