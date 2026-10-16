package com.fairchain.example.dto;

import jakarta.validation.constraints.NotBlank;

/** C-0102 로그인 요청 — 회원 · 스태프 · 운영자 공용 */
public record LoginRequestDto(@NotBlank String loginId, @NotBlank String password) {}
