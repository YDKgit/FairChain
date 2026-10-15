package com.fairchain.server.admin.controller;

import com.fairchain.server.admin.dto.LoginRequestDto;
import com.fairchain.server.admin.dto.LoginResponseDto;
import com.fairchain.server.admin.entity.OperatorAccount;
import com.fairchain.server.admin.service.AuthService;
import com.fairchain.server.admin.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 운영자가 브라우저로 직접 접속하는 관제 대시보드 (apiKey가 아니라 대시보드 토큰으로 인증) */
@RestController
public class DashboardController {

    private final AuthService authService;
    private final DashboardService dashboardService;

    public DashboardController(AuthService authService, DashboardService dashboardService) {
        this.authService = authService;
        this.dashboardService = dashboardService;
    }

    @PostMapping("/dashboard/login")
    public LoginResponseDto login(@RequestBody LoginRequestDto dto) {
        return authService.login(dto);
    }

    /** EventSource는 헤더를 못 붙이므로 토큰을 쿼리로 받는다 */
    @GetMapping("/dashboard/subscribe")
    public SseEmitter subscribe(@RequestParam("token") String dashboardToken) {
        OperatorAccount account = authService.authenticateDashboardToken(dashboardToken);
        return dashboardService.register(account.getClientId());
    }
}
