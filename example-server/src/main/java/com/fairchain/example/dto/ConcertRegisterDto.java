package com.fairchain.example.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/** C-0602 콘서트 등록 · 수정 요청
 *
 * @param seatGrades 보완 — 함께 만들 좌석 등급 (nullable, 등록 시에만 사용)
 */
public record ConcertRegisterDto(
        @NotBlank String title,
        @NotNull LocalDateTime eventOpen,
        @NotNull LocalDateTime eventStart,
        @NotNull LocalDateTime eventEnd,
        @NotBlank String venue,
        List<@Valid SeatGradeDto> seatGrades
) {}
