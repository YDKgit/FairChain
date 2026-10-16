package com.fairchain.example.requester;

import com.fairchain.api.dto.AnchorListRequestDto;
import com.fairchain.api.dto.AnchorListResponseDto;

/** C-0510 티켓 목록 검증 FairChain 접근 */
public interface TicketVerificationAccess {

    AnchorListResponseDto getVerifiedAnchors(AnchorListRequestDto dto);
}
