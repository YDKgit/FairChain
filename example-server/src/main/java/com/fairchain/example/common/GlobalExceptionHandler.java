package com.fairchain.example.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/** 모든 오류를 {message} 형태로 — 화면은 message를 그대로 보여 준다 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handle(ApiException e) {
        return body(e.status(), e.getMessage());
    }

    /** 본문이 JSON이 아니거나 파라미터가 빠진 요청 */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "없는 경로입니다");
    }

    /** FairChain이 거절한 요청 — FairChain이 준 사유를 그대로 전달 */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, String>> fairChainRejected(RestClientResponseException e) {
        return body(HttpStatus.BAD_GATEWAY, "FairChain: " + fairChainMessage(e));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, String>> fairChainDown(ResourceAccessException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "FairChain 서버에 연결할 수 없습니다");
    }

    /** SSE(대기열 순번) 연결이 시간 초과로 닫힘 — 오류가 아니다 */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> sseTimeout(AsyncRequestTimeoutException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("처리되지 않은 오류", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }

    private String fairChainMessage(RestClientResponseException e) {
        try {
            JsonNode body = objectMapper.readTree(e.getResponseBodyAsString());
            if (body != null && body.hasNonNull("message")) {
                return body.get("message").asText();
            }
        } catch (Exception ignored) {
            // 본문이 JSON이 아니면 상태 코드만
        }
        return "HTTP " + e.getStatusCode().value();
    }
}
