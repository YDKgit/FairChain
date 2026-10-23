package com.fairchain.example.common;

import jakarta.servlet.http.HttpSession;

/** 세션에 담는 로그인 정보 — 회원 · 게이트 스태프 · 운영자는 각각 따로 로그인한다 */
public final class SessionUser {

    private static final String MEMBER = "memberId";
    private static final String STAFF = "staffId";
    private static final String OPERATOR = "operatorId";

    private SessionUser() {}

    public static void loginMember(HttpSession session, Long memberId) {
        session.setAttribute(MEMBER, memberId);
    }

    public static void loginStaff(HttpSession session, Long staffId) {
        session.setAttribute(STAFF, staffId);
    }

    public static void loginOperator(HttpSession session, Long operatorId) {
        session.setAttribute(OPERATOR, operatorId);
    }

    public static Long memberId(HttpSession session) {
        return require(session, MEMBER, "로그인이 필요합니다");
    }

    public static Long staffId(HttpSession session) {
        return require(session, STAFF, "게이트 스태프 로그인이 필요합니다");
    }

    public static Long operatorId(HttpSession session) {
        return require(session, OPERATOR, "운영자 로그인이 필요합니다");
    }

    public static Long memberIdOrNull(HttpSession session) {
        return (Long) session.getAttribute(MEMBER);
    }

    public static void logoutMember(HttpSession session) {
        session.removeAttribute(MEMBER);
    }

    public static void logoutStaff(HttpSession session) {
        session.removeAttribute(STAFF);
    }

    public static void logoutOperator(HttpSession session) {
        session.removeAttribute(OPERATOR);
    }

    private static Long require(HttpSession session, String attribute, String message) {
        Object id = session.getAttribute(attribute);
        if (id == null) {
            throw ApiException.unauthorized(message);
        }
        return (Long) id;
    }
}
