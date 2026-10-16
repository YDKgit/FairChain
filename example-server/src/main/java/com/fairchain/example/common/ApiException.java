package com.fairchain.example.common;

import org.springframework.http.HttpStatus;

/** 업무 오류 — GlobalExceptionHandler가 status · code로 응답 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
