package com.fairchain.example.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** C-0601 좌석 등록 요청 */
public record SeatRegisterDto(@NotNull Long concertId, @NotNull Long seatGradeId, @NotEmpty List<String> seatNumbers) {}
