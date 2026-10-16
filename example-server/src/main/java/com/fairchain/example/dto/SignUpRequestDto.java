package com.fairchain.example.dto;

import jakarta.validation.constraints.NotBlank;

/** C-0101 회원가입 요청 */
public record SignUpRequestDto(
        @NotBlank String loginId,
        @NotBlank String password,
        @NotBlank String name,
        @NotBlank String contact
) {}
