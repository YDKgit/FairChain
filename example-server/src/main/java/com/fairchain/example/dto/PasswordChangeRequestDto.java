package com.fairchain.example.dto;

import jakarta.validation.constraints.NotBlank;

/** C-0103 비밀번호 변경 요청 */
public record PasswordChangeRequestDto(@NotBlank String currentPassword, @NotBlank String newPassword) {}
