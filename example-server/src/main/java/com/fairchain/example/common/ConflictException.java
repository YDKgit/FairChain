package com.fairchain.example.common;

import org.springframework.http.HttpStatus;

/** 409 — 아이디 중복 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
