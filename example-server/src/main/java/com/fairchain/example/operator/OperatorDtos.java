package com.fairchain.example.operator;

import com.fairchain.example.concert.ConcertDtos.SeatGradeDto;

import java.time.LocalDateTime;
import java.util.List;

/** 운영 관리 DTO (CD-06-1) */
public final class OperatorDtos {

    private OperatorDtos() {}

    public record OperatorLoginDto(String loginId, String password) {}

    /** @param seatNumbers "A1", "A2" … 또는 범위 "A1-A10" */
    public record SeatRegisterDto(Long concertId, Long seatGradeId, List<String> seatNumbers) {}

    /** @param seatGrades 등록 때만 사용 (수정 때는 무시) */
    public record ConcertRegisterDto(String title, LocalDateTime eventOpen, LocalDateTime eventStart,
                                     LocalDateTime eventEnd, String venue, List<SeatGradeDto> seatGrades) {}

    /** @param fairChainUrl 관제 대시보드 · 점검 화면 링크용 */
    public record KeysDto(String apiKey, String dashboardToken, String fairChainUrl) {}
}
