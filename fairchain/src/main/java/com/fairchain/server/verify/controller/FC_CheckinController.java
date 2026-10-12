package com.fairchain.server.verify.controller;

import com.fairchain.api.CheckinApi;
import com.fairchain.api.dto.VerifyRequestDto;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.server.common.ClientContext;
import com.fairchain.server.verify.service.VerifyService;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FC_CheckinController implements CheckinApi {

    private final VerifyService verifyService;

    public FC_CheckinController(VerifyService verifyService) {
        this.verifyService = verifyService;
    }

    @Override
    public VerifyResultDto verify(VerifyRequestDto dto) {
        return verifyService.verify(ClientContext.clientId(), dto);
    }
}
