package com.fairchain.example.dto;

import jakarta.validation.constraints.NotBlank;

/** C-0401 게이트 스태프 등록 요청 */
public record GateStaffRegisterDto(@NotBlank String loginId, @NotBlank String password, @NotBlank String name) {}
