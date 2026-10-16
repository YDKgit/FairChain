package com.fairchain.example.dto;

import com.fairchain.example.entity.TicketStatus;
import com.fairchain.example.entity.VerificationStatus;

import java.math.BigDecimal;

/** C-0505 티켓 상세 — MISSING_LOCAL 항목은 verificationStatus 외 모두 null */
public record TicketDetailResponseDto(
        Long ticketId,
        String concertTitle,
        String seatNumber,
        String seatGrade,
        BigDecimal price,
        TicketStatus status,
        VerificationStatus verificationStatus,
        String qrToken
) {

    public static TicketDetailResponseDto missingLocal() {
        return new TicketDetailResponseDto(null, null, null, null, null, null, VerificationStatus.MISSING_LOCAL, null);
    }
}
