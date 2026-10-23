package com.fairchain.example.concert;

import com.fairchain.example.common.ApiException;
import com.fairchain.example.concert.ConcertDtos.ConcertDetailDto;
import com.fairchain.example.concert.ConcertDtos.ConcertListDto;
import com.fairchain.example.concert.ConcertDtos.ConcertSearchCondition;
import com.fairchain.example.concert.ConcertDtos.SeatGradeDto;
import com.fairchain.example.reservation.SeatRepository;
import com.fairchain.example.reservation.SeatStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 콘서트 조회 — FairChain을 거치지 않는 예제서버 자체 기능 */
@Service
public class ConcertQueryService {

    private final ConcertRepository concertRepository;
    private final SeatGradeRepository seatGradeRepository;
    private final SeatRepository seatRepository;

    public ConcertQueryService(ConcertRepository concertRepository, SeatGradeRepository seatGradeRepository,
                               SeatRepository seatRepository) {
        this.concertRepository = concertRepository;
        this.seatGradeRepository = seatGradeRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional(readOnly = true)
    public List<ConcertListDto> getConcertList(ConcertSearchCondition condition) {
        LocalDateTime now = LocalDateTime.now();
        return concertRepository.search(emptyToNull(condition.keyword()), emptyToNull(condition.venue())).stream()
                .map(c -> new ConcertListDto(c.getId(), c.getTitle(), c.getEventOpen(), c.getEventStart(),
                        c.getEventEnd(), c.getVenue(), c.isOpen(now)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConcertDetailDto getConcertDetail(Long concertId) {
        Concert c = getConcert(concertId);
        List<SeatGradeDto> grades = seatGradeRepository.findByConcertId(concertId).stream()
                .map(g -> new SeatGradeDto(g.getId(), g.getGradeName(), g.getPrice()))
                .toList();
        long total = seatRepository.countByConcertId(concertId);
        long empty = seatRepository.countByConcertIdAndStatus(concertId, SeatStatus.EMPTY);
        return new ConcertDetailDto(c.getId(), c.getTitle(), c.getEventOpen(), c.getEventStart(), c.getEventEnd(),
                c.getVenue(), c.isOpen(LocalDateTime.now()), grades, total, empty);
    }

    @Transactional(readOnly = true)
    public Concert getConcert(Long concertId) {
        return concertRepository.findById(concertId)
                .orElseThrow(() -> ApiException.notFound("공연이 없습니다: " + concertId));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
