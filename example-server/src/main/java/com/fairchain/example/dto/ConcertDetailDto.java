package com.fairchain.example.dto;

import java.time.LocalDateTime;
import java.util.List;

/** C-0703 콘서트 상세 */
public record ConcertDetailDto(Long id, String title, LocalDateTime eventOpen, LocalDateTime eventStart,
                               LocalDateTime eventEnd, String venue, List<SeatGradeDto> seatGrades) {}
