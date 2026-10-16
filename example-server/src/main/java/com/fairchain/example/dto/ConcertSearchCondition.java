package com.fairchain.example.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** C-0701 콘서트 검색 조건 — 모두 nullable, null이면 그 조건은 무시
 *
 * @param from eventStart 날짜 범위 시작 (포함)
 * @param to   eventStart 날짜 범위 끝 (포함)
 */
public record ConcertSearchCondition(
        String keyword,
        String venue,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
) {

    public boolean isEmpty() {
        return isBlank(keyword) && isBlank(venue) && from == null && to == null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
