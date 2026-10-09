package com.fairchain.api.dto;

/** 체크인 검증 — 티켓 내용이 아니라 지문만 보낸다 */
public record VerifyRequestDto(String recordKey, Long version, String recordHash) {}
