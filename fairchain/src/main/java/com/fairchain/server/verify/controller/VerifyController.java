package com.fairchain.server.verify.controller;

import com.fairchain.api.TicketVerificationApi;
import com.fairchain.api.dto.AnchorListRequestDto;
import com.fairchain.api.dto.AnchorListResponseDto;
import com.fairchain.server.common.ClientContext;
import com.fairchain.server.verify.service.VerifyService;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VerifyController implements TicketVerificationApi {

    private final VerifyService verifyService;

    public VerifyController(VerifyService verifyService) {
        this.verifyService = verifyService;
    }

    @Override
    public AnchorListResponseDto getVerifiedAnchors(AnchorListRequestDto dto) {
        return verifyService.getVerifiedAnchors(ClientContext.clientId(), dto);
    }
}
