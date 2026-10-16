package com.fairchain.example.common;

import org.springframework.http.HttpStatus;

/** 400 — 필수값 누락 · 형식 오류 · 비밀번호 불일치 · 취소 불가 상태 */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }
}
