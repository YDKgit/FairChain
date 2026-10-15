package com.fairchain.example.reservation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 예매 관리 DTO (CD-03-1) */
public final class ReservationDtos {

    private ReservationDtos() {}

    /** @param offerToken 재배정 제안으로 예매할 때만 */
    public record ReserveRequestDto(Long seatId, Long concertId, String offerToken) {}

    public record ReserveResultDto(boolean success, Long ticketId, String reason) {

        static ReserveResultDto ok(Long ticketId) {
            return new ReserveResultDto(true, ticketId, null);
        }

        static ReserveResultDto fail(String reason) {
            return new ReserveResultDto(false, null, reason);
        }
    }

    public record SeatDto(Long seatId, String seatNumber, String gradeName, BigDecimal price, SeatStatus status) {}

    public record SeatListResponseDto(List<SeatDto> seats) {}

    public record OfferDto(Long offerId, Long concertId, String concertTitle, Long seatId, String seatNumber,
                           String offerToken, LocalDateTime expiresAt) {}
}
