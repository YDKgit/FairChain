package com.fairchain.example.dto;

import jakarta.validation.constraints.NotBlank;

/** C-0105 회원 탈퇴 요청 */
public record WithdrawRequestDto(@NotBlank String password) {}
