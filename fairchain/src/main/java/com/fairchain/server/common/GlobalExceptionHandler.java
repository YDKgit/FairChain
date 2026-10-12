package com.fairchain.server.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(FairChainException.class)
    public ResponseEntity<Map<String, String>> handle(FairChainException e) {
        return ResponseEntity.status(e.errorCode().status())
                .body(Map.of("code", e.errorCode().name(), "message", e.getMessage()));
    }
}
