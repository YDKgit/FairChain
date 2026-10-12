package com.fairchain.server.common.config;

import com.fairchain.server.common.ClientContext;
import com.fairchain.server.common.ErrorCode;
import com.fairchain.server.common.FairChainException;
import com.fairchain.server.queue.repository.IdempotencyKeyCache;
import com.fairchain.server.queue.repository.MacroBlockCounter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 매크로 차단(EX-03) · 중복 요청(EX-05).
 * - 회원 1명이 1초에 한도를 넘게 요청하면 429
 * - 같은 Idempotency-Key로 다시 오면 처음 응답을 그대로 돌려줌 (저장은 IdempotencyResponseAdvice → afterCompletion)
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String RESPONSE_ATTR = "fairchain.idempotentResponse";

    private final MacroBlockCounter macroBlockCounter;
    private final IdempotencyKeyCache idempotencyKeyCache;
    private final FairChainProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(MacroBlockCounter macroBlockCounter, IdempotencyKeyCache idempotencyKeyCache,
                                FairChainProperties properties, ObjectMapper objectMapper) {
        this.macroBlockCounter = macroBlockCounter;
        this.idempotencyKeyCache = idempotencyKeyCache;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        Long userId = readLong(request, "userId");
        if (userId != null) {
            long count = macroBlockCounter.increment(userId, System.currentTimeMillis() / 1000);
            if (count > properties.rateLimit().perSecond()) {
                Long eventId = readLong(request, "eventId");
                if (eventId != null) {
                    macroBlockCounter.incrementBlocked(eventId);
                }
                throw new FairChainException(ErrorCode.TOO_MANY_REQUESTS, "요청이 너무 많습니다");
            }
        }

        String key = idempotencyKey(request);
        if (key != null) {
            String cached = idempotencyKeyCache.find(key);
            if (cached != null) {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write(cached);
                return false;
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
            throws IOException {
        String key = idempotencyKey(request);
        Object body = request.getAttribute(RESPONSE_ATTR);
        if (key != null && body != null && ex == null && response.getStatus() == HttpServletResponse.SC_OK) {
            idempotencyKeyCache.save(key, objectMapper.writeValueAsString(body), properties.rateLimit().idempotencyTtl());
        }
    }

    /** 고객사마다 키 범위를 나눈다 (다른 고객사가 같은 키를 써도 섞이지 않게) */
    private String idempotencyKey(HttpServletRequest request) {
        String key = request.getHeader(IDEMPOTENCY_KEY);
        return key == null ? null : ClientContext.clientId() + ":" + key;
    }

    /** 쿼리 파라미터 → JSON 본문 순서로 찾는다 */
    private Long readLong(HttpServletRequest request, String name) {
        String param = request.getParameter(name);
        if (param != null) {
            return parseLong(param);
        }
        if (request instanceof CachedBodyFilter.CachedBodyRequest cached && cached.body().length > 0) {
            try {
                JsonNode node = objectMapper.readTree(cached.body()).get(name);
                return node != null && node.canConvertToLong() ? node.asLong() : null;
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
