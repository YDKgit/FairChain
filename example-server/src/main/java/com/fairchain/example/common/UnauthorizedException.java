package com.fairchain.example.common;

import org.springframework.http.HttpStatus;

/** 401 — 로그인 실패 · 세션 없음 */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }
}
