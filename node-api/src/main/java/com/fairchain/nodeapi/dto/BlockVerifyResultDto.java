package com.fairchain.nodeapi.dto;

/** @param valid 노드의 최신 기록이 요청한 버전 · 지문과 같으면 true */
public record BlockVerifyResultDto(boolean valid, Long latestVersion, String latestRecordHash) {}
