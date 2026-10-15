package com.fairchain.server.common;

public class FairChainException extends RuntimeException {

    private final ErrorCode errorCode;

    public FairChainException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
