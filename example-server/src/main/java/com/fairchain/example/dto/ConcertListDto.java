package com.fairchain.example.dto;

import com.fairchain.example.entity.Concert;

import java.time.LocalDateTime;

/** C-0702 콘서트 목록 항목 */
public record ConcertListDto(Long id, String title, LocalDateTime eventOpen, LocalDateTime eventStart,
                             LocalDateTime eventEnd, String venue) {

    public static ConcertListDto from(Concert concert) {
        return new ConcertListDto(concert.getId(), concert.getTitle(), concert.getEventOpen(),
                concert.getEventStart(), concert.getEventEnd(), concert.getVenue());
    }
}
