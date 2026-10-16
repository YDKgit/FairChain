package com.fairchain.example.dto;

import java.time.LocalDateTime;

/** 보완 — 내 재배정 제안 (앱 푸시 알림 대용 조회) */
public record ReallocationOfferDto(Long offerId, Long concertId, Long seatId, String seatNumber,
                                   String offerToken, LocalDateTime expiresAt) {}
