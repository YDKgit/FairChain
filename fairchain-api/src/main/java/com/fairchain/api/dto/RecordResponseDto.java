package com.fairchain.api.dto;

/** @param soldOut 매진 이후의 취소면 true → 예제서버가 재배정을 시작 */
public record RecordResponseDto(boolean soldOut) {}
