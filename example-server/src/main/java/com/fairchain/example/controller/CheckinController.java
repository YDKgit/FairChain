package com.fairchain.example.controller;

import com.fairchain.example.common.LoginOperator;
import com.fairchain.example.common.LoginStaff;
import com.fairchain.example.common.SessionConst;
import com.fairchain.example.dto.CheckinResultDto;
import com.fairchain.example.dto.GateStaffRegisterDto;
import com.fairchain.example.dto.LoginRequestDto;
import com.fairchain.example.dto.ScanRequestDto;
import com.fairchain.example.service.CheckinService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** C-0403 체크인 (SS-04) */
@RestController
public class CheckinController {

    private final CheckinService checkinService;

    public CheckinController(CheckinService checkinService) {
        this.checkinService = checkinService;
    }

    @PostMapping("/staff/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequestDto dto, HttpServletRequest request) {
        var staff = checkinService.login(dto);
        SessionSupport.login(request, SessionConst.LOGIN_STAFF_ID, staff.getStaffId());
        return Map.of("staffId", staff.getStaffId(), "name", staff.getName());
    }

    @PostMapping("/staff/scan")
    public CheckinResultDto scan(@LoginStaff Long staffId, @Valid @RequestBody ScanRequestDto dto) {
        return checkinService.scanQr(dto.qrToken());
    }

    @PostMapping("/admin/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registerStaff(@LoginOperator Long operatorId, @Valid @RequestBody GateStaffRegisterDto dto) {
        var staff = checkinService.registerStaff(dto);
        return Map.of("staffId", staff.getStaffId(), "loginId", staff.getLoginId(), "name", staff.getName());
    }
}
