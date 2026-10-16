package com.fairchain.example.common;

import org.springframework.http.HttpStatus;

/** 403 — 예매 오픈 전 · 운영자 세션 아님 · 남의 티켓 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
