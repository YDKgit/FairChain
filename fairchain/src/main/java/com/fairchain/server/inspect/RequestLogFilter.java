package com.fairchain.server.inspect;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * /api/** 요청을 기록해 점검 화면(inspector.html)에서 보여 준다.
 * 본문은 흘려보내면서 복사만 하므로 다른 필터 · 인터셉터의 동작은 그대로다. SSE 구독은 응답 본문을 기록하지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final int BODY_LIMIT = 2000;
    /** ClientContext가 인증된 고객사 ID를 넣어 두는 요청 속성 */
    private static final String CLIENT_ATTR = "fairchain.clientId";

    private final RequestLogStore store;

    public RequestLogFilter(RequestLogStore store) {
        this.store = store;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.currentTimeMillis();
        boolean stream = request.getRequestURI().endsWith("/subscribe");
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request, BODY_LIMIT);
        ContentCachingResponseWrapper res = stream ? null : new ContentCachingResponseWrapper(response);
        HttpServletResponse out = res == null ? response : res;
        try {
            chain.doFilter(req, out);
        } finally {
            String responseBody = res == null ? "(SSE)" : text(res.getContentAsByteArray());
            int status = out.getStatus();
            if (res != null) {
                res.copyBodyToResponse();
            }
            String query = request.getQueryString();
            Object clientId = request.getAttribute(CLIENT_ATTR);
            store.add(new RequestLogEntry(store.nextId(), LocalDateTime.now(), request.getMethod(),
                    request.getRequestURI() + (query == null ? "" : "?" + query),
                    clientId == null ? null : clientId.toString(), status,
                    System.currentTimeMillis() - started, text(req.getContentAsByteArray()), responseBody));
        }
    }

    private static String text(byte[] body) {
        String value = new String(body, StandardCharsets.UTF_8);
        return value.length() > BODY_LIMIT ? value.substring(0, BODY_LIMIT) + "…" : value;
    }
}
