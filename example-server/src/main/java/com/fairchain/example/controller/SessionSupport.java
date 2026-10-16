package com.fairchain.example.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/** 로그인 세션 생성 · 종료 — 로그인 시 기존 세션은 만료하고 새로 만든다 */
final class SessionSupport {

    private SessionSupport() {}

    static void login(HttpServletRequest request, String key, Long id) {
        invalidate(request);
        request.getSession(true).setAttribute(key, id);
    }

    static void invalidate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
