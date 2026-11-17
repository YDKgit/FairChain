package com.fairchain.server.common;

/** 모든 오류 응답의 모양 — {code, message}. 예제서버는 message를 화면에 그대로 보여 준다 */
public record ErrorResponse(String code, String message) {}
