package com.fairchain.example.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * C-0704 좌석 등급
 *
 * @param seatGradeId 보완 — 응답에서만 채운다(좌석 등록에 필요). 등록 요청에서는 무시
 */
public record SeatGradeDto(Long seatGradeId, @NotBlank String gradeName, @NotNull BigDecimal price) {}
