package com.fairchain.example.common;

import org.springframework.http.HttpStatus;

/** 404 — 콘서트 · 티켓 없음 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
