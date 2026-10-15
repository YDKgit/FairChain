package com.fairchain.server.inspect;

import com.fairchain.server.admin.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 점검 화면 (inspector.html) — 관제 대시보드처럼 운영자 대시보드 토큰으로 인증 */
@RestController
@RequestMapping("/dashboard/inspect")
public class InspectorController {

    private final AuthService authService;
    private final InspectorService inspectorService;

    public InspectorController(AuthService authService, InspectorService inspectorService) {
        this.authService = authService;
        this.inspectorService = inspectorService;
    }

    @GetMapping("/requests")
    public List<RequestLogEntry> requests(@RequestParam("token") String token) {
        return inspectorService.requests(clientId(token));
    }

    @GetMapping("/db")
    public InspectorService.DbSnapshot db(@RequestParam("token") String token) {
        return inspectorService.db(clientId(token));
    }

    @GetMapping("/nodes")
    public List<InspectorService.NodeView> nodes(@RequestParam("token") String token) {
        clientId(token);
        return inspectorService.nodes();
    }

    private String clientId(String token) {
        return authService.authenticateDashboardToken(token).getClientId();
    }
}
