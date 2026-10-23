package com.fairchain.example.concert;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 콘서트 조회 DTO (CD-07) */
public final class ConcertDtos {

    private ConcertDtos() {}

    public record ConcertSearchCondition(String keyword, String venue) {}

    /** @param open 지금 예매 가능(오픈 시각이 지남)한지 */
    public record ConcertListDto(Long id, String title, LocalDateTime eventOpen, LocalDateTime eventStart,
                                 LocalDateTime eventEnd, String venue, boolean open) {}

    public record SeatGradeDto(Long id, String gradeName, BigDecimal price) {}

    /** @param totalSeats 등록된 좌석 수 · @param emptySeats 지금 예매 가능한 좌석 수 */
    public record ConcertDetailDto(Long id, String title, LocalDateTime eventOpen, LocalDateTime eventStart,
                                   LocalDateTime eventEnd, String venue, boolean open, List<SeatGradeDto> seatGrades,
                                   long totalSeats, long emptySeats) {}
}
