package com.fairchain.server.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 예외 → HTTP 상태 변환은 여기 한 곳에서만. 응답 본문은 항상 ErrorResponse */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FairChainException.class)
    public ResponseEntity<ErrorResponse> handle(FairChainException e) {
        return ResponseEntity.status(e.errorCode().status()).body(new ErrorResponse(e.errorCode().name(), e.getMessage()));
    }

    /** 본문이 JSON이 아니거나, 쿼리 파라미터가 빠졌거나 타입이 틀린 요청 */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ErrorCode.BAD_REQUEST.name(), "요청 형식이 올바르지 않습니다"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ErrorCode.NOT_FOUND.name(), "없는 경로입니다"));
    }

    /** SSE 연결이 시간 초과로 닫힘 — 오류가 아니므로 로그 없이 */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> sseTimeout(AsyncRequestTimeoutException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    /** 예상하지 못한 오류 — 내용은 로그에만 남기고 응답에는 내부 정보를 싣지 않는다 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("처리되지 않은 오류", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다"));
    }
}
