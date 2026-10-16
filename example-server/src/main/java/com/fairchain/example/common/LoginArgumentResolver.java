package com.fairchain.example.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** @LoginMember · @LoginStaff · @LoginOperator 처리 — 세션에 id가 없으면 401(운영자는 403) */
public class LoginArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Long.class.equals(parameter.getParameterType())
                && (parameter.hasParameterAnnotation(LoginMember.class)
                || parameter.hasParameterAnnotation(LoginStaff.class)
                || parameter.hasParameterAnnotation(LoginOperator.class));
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpSession session = webRequest.getNativeRequest(HttpServletRequest.class).getSession(false);
        if (parameter.hasParameterAnnotation(LoginMember.class)) {
            return require(session, SessionConst.LOGIN_MEMBER_ID, new UnauthorizedException("로그인이 필요합니다"));
        }
        if (parameter.hasParameterAnnotation(LoginStaff.class)) {
            return require(session, SessionConst.LOGIN_STAFF_ID, new UnauthorizedException("스태프 로그인이 필요합니다"));
        }
        return require(session, SessionConst.LOGIN_OPERATOR_ID, new ForbiddenException("운영자만 사용할 수 있습니다"));
    }

    private Long require(HttpSession session, String key, ApiException whenMissing) {
        Object id = session == null ? null : session.getAttribute(key);
        if (id instanceof Long value) {
            return value;
        }
        throw whenMissing;
    }
}
