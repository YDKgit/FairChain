package com.fairchain.server.common.config;

import com.fairchain.api.http.FairChainHeaders;
import com.fairchain.server.admin.service.AuthService;
import com.fairchain.server.common.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 모든 /api/** 요청의 apiKey 검사 → clientId (EX-04: 무효면 401).
 * 맞는 키는 기억해 둔다 — 고객사의 API Key는 만든 뒤 바뀌지 않고, 대기 순번 확인처럼 수천 명이 주기적으로 부르는 요청이
 * 매번 DB를 읽지 않게. 틀린 키는 기억하지 않는다 (아무 키나 보내 메모리를 채울 수 없게).
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final AuthService authService;
    private final Map<String, String> clientByKey = new ConcurrentHashMap<>();

    public ApiKeyInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String apiKey = request.getHeader(FairChainHeaders.API_KEY);
        String clientId = apiKey == null ? null : clientByKey.get(apiKey);
        if (clientId == null) {
            clientId = authService.authenticateApiKey(apiKey).getClientId();     // 없거나 틀리면 401
            clientByKey.put(apiKey, clientId);
        }
        ClientContext.set(clientId);
        return true;
    }
}
