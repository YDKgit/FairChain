package com.fairchain.nodeapi.dto;

/** @param blockIndex 확정된 블록 번호 (accepted가 false면 null) */
public record BlockResultDto(boolean accepted, Long blockIndex) {}
