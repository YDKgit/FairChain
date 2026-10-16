package com.fairchain.example.dto;

import jakarta.validation.constraints.NotBlank;

/** C-0104 회원 정보 수정 요청 */
public record MemberUpdateRequestDto(@NotBlank String name, @NotBlank String contact) {}
