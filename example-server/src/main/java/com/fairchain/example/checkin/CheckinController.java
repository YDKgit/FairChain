package com.fairchain.example.checkin;

import com.fairchain.example.checkin.CheckinDtos.CheckinResultDto;
import com.fairchain.example.checkin.CheckinDtos.GateStaffRegisterDto;
import com.fairchain.example.checkin.CheckinDtos.ScanRequestDto;
import com.fairchain.example.checkin.CheckinDtos.StaffLoginDto;
import com.fairchain.example.common.SessionUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 게이트 스태프 API. /api/staff/** 는 login · logout 외에 SessionAuthInterceptor가 스태프 세션을 먼저 확인한다 */
@RestController
public class CheckinController {

    private final CheckinService checkinService;

    public CheckinController(CheckinService checkinService) {
        this.checkinService = checkinService;
    }

    @PostMapping("/api/staff/login")
    public Map<String, String> login(@RequestBody StaffLoginDto dto, HttpSession session) {
        GateStaff staff = checkinService.login(dto);
        SessionUser.loginStaff(session, staff.getStaffId());
        return Map.of("name", staff.getName());
    }

    @PostMapping("/api/staff/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        SessionUser.logoutStaff(session);
        return ResponseEntity.ok().build();
    }

    /** 로그인된 스태프 — 게이트 화면이 세션이 살아 있는지 확인할 때 */
    @GetMapping("/api/staff/me")
    public Map<String, String> me(HttpSession session) {
        return Map.of("name", checkinService.staff(SessionUser.staffId(session)).getName());
    }

    @PostMapping("/api/staff/scan")
    public CheckinResultDto scan(@RequestBody ScanRequestDto dto) {
        return checkinService.scanQr(dto == null ? null : dto.qrToken());
    }

    /** 운영자만 게이트 스태프 계정을 만든다 (/api/operator/** → 운영자 세션 확인) */
    @PostMapping("/api/operator/staff")
    public ResponseEntity<Void> registerStaff(@RequestBody GateStaffRegisterDto dto) {
        checkinService.registerStaff(dto);
        return ResponseEntity.ok().build();
    }
}
