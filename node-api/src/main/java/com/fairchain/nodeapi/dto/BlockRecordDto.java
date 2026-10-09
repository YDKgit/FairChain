package com.fairchain.nodeapi.dto;

/** 노드에 기록하는 값 — 개인정보 없이 좌석 키 · 버전 · 지문만 */
public record BlockRecordDto(String recordKey, Long version, String recordHash) {}
