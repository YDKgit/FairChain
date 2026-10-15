package com.fairchain.example.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/** 모든 오류를 {message} 형태로 — 화면은 message를 그대로 보여 준다 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handle(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage()));
    }

    /** FairChain이 거절한 요청 — FairChain이 준 사유를 그대로 전달 */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, String>> fairChainRejected(RestClientResponseException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("message", "FairChain: " + fairChainMessage(e)));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, String>> fairChainDown(ResourceAccessException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "FairChain 서버에 연결할 수 없습니다"));
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
