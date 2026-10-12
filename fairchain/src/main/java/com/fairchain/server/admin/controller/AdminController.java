package com.fairchain.server.admin.controller;

import com.fairchain.api.AdminApi;
import com.fairchain.api.dto.ApiKeyDto;
import com.fairchain.api.dto.SeatIssueRequestDto;
import com.fairchain.server.admin.service.AdminService;
import com.fairchain.server.common.ClientContext;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController implements AdminApi {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @Override
    public ApiKeyDto getKeys() {
        return adminService.getKeys(ClientContext.clientId());
    }

    @Override
    public void issueSeats(SeatIssueRequestDto dto) {
        adminService.issueSeats(ClientContext.clientId(), dto);
    }
}
