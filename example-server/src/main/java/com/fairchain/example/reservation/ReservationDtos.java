package com.fairchain.example.reservation;

import com.fairchain.example.concert.SeatGrade;

import java.math.BigDecimal;
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

    public record SeatDto(Long seatId, String seatNumber, String gradeName, BigDecimal price, SeatStatus status) {

        /** 좌석 + 등급(없으면 null) → 화면용 */
        public static SeatDto of(Seat seat, SeatGrade grade) {
            return new SeatDto(seat.getId(), seat.getSeatNumber(), SeatGrade.nameOf(grade),
                    grade == null ? null : grade.getPrice(), seat.getStatus());
        }
    }

    public record SeatListResponseDto(List<SeatDto> seats) {}
}
