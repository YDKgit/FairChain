package com.fairchain.example.dto;

import com.fairchain.example.entity.SeatStatus;

import java.math.BigDecimal;

/** C-0304 좌석 */
public record SeatDto(Long seatId, String seatNumber, String gradeName, BigDecimal price, SeatStatus status) {}
