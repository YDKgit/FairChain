package com.fairchain.server.common;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * ApiKeyInterceptor가 확인한 고객사 ID를 요청 범위에 보관.
 * Controller는 fairchain-api 인터페이스 시그니처를 그대로 따르므로 clientId를 여기서 꺼낸다.
 */
public final class ClientContext {

    private static final String ATTR = "fairchain.clientId";

    private ClientContext() {}

    public static void set(String clientId) {
        RequestContextHolder.currentRequestAttributes().setAttribute(ATTR, clientId, RequestAttributes.SCOPE_REQUEST);
    }

    public static String clientId() {
        Object clientId = RequestContextHolder.currentRequestAttributes().getAttribute(ATTR, RequestAttributes.SCOPE_REQUEST);
        if (clientId == null) {
            throw new FairChainException(ErrorCode.UNAUTHORIZED, "인증된 고객사가 없습니다");
        }
        return (String) clientId;
    }
}
