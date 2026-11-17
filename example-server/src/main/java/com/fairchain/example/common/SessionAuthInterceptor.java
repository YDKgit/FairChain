package com.fairchain.example.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 운영자 · 게이트 스태프 전용 경로는 컨트롤러에 들어가기 전에 세션을 확인한다.
 * /api/operator/** 는 운영자, /api/staff/** 는 스태프 로그인이 있어야 한다 (login · logout 제외).
 * 회원 전용 경로는 memberId가 필요해 컨트롤러가 SessionUser.memberId로 직접 꺼낸다.
 */
@Component
public class SessionAuthInterceptor implements HandlerInterceptor {

    static final String OPERATOR_PREFIX = "/api/operator/";
    static final String STAFF_PREFIX = "/api/staff/";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (path.endsWith("/login") || path.endsWith("/logout")) {
            return true;
        }
        if (path.startsWith(OPERATOR_PREFIX)) {
            SessionUser.operatorId(request.getSession());
        } else if (path.startsWith(STAFF_PREFIX)) {
            SessionUser.staffId(request.getSession());
        }
        return true;
    }
}
