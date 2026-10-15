package com.fairchain.example.ticket;

import com.fairchain.example.reservation.TicketStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 티켓 관리 DTO (CD-05-1) */
public final class TicketDtos {

    private TicketDtos() {}

    /**
     * @param ticketId          로컬 티켓이 없는 기록(MISSING_LOCAL)이면 null
     * @param blockIndex        FairChain이 알려 준 블록 번호 (확정 전이면 null)
     */
    public record TicketDetailResponseDto(Long ticketId, Long concertId, String concertTitle, String seatNumber,
                                          String seatGrade, BigDecimal price, TicketStatus status, Long version,
                                          VerificationStatus verificationStatus, String qrToken,
                                          LocalDateTime issuedAt, LocalDateTime checkedInAt, Long blockIndex) {}

    /** @param verificationAvailable 노드 확인이 가능했는지 */
    public record TicketListResponseDto(List<TicketDetailResponseDto> tickets, boolean verificationAvailable) {}
}
