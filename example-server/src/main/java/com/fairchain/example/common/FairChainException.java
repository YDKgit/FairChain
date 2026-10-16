package com.fairchain.example.common;

import org.springframework.http.HttpStatus;

/**
 * FairChain 호출 실패 — Requester 계층이 HttpRequester 예외를 감싼다.
 * FairChain이 429(매크로 차단)면 429, 그 외 오류 · 타임아웃은 502로 응답한다.
 */
public class FairChainException extends RuntimeException {

    /** FairChain 응답 상태코드. 응답을 받지 못했으면(타임아웃 · 연결 실패) 0 */
    private final int httpStatus;

    public FairChainException(int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public HttpStatus responseStatus() {
        return httpStatus == HttpStatus.TOO_MANY_REQUESTS.value() ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
    }
}
