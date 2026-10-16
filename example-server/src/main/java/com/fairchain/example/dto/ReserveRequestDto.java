package com.fairchain.example.dto;

import jakarta.validation.constraints.NotNull;

/** C-0301 예매 요청
 *
 * @param offerToken 재배정 제안 토큰 (보완). 있으면 재배정 예매, 없으면 일반 예매
 */
public record ReserveRequestDto(@NotNull Long seatId, @NotNull Long concertId, String offerToken) {}
