package com.fairchain.server.common.config;

import com.fairchain.api.http.FairChainHeaders;
import com.fairchain.server.admin.entity.Clients;
import com.fairchain.server.admin.service.AuthService;
import com.fairchain.server.common.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 모든 /api/** 요청의 apiKey 검사 → clientId (EX-04: 무효면 401) */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public ApiKeyInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Clients client = authService.authenticateApiKey(request.getHeader(FairChainHeaders.API_KEY));
        ClientContext.set(client.getClientId());
        return true;
    }
}
