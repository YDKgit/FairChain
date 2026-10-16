package com.fairchain.example.service;

import com.fairchain.example.common.NotFoundException;
import com.fairchain.example.dto.ConcertDetailDto;
import com.fairchain.example.dto.ConcertListDto;
import com.fairchain.example.dto.ConcertSearchCondition;
import com.fairchain.example.dto.SeatGradeDto;
import com.fairchain.example.entity.Concert;
import com.fairchain.example.repository.ConcertRepository;
import com.fairchain.example.repository.SeatGradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** C-0706 콘서트 조회 — 예매 상태 · 매진 · 잔여 좌석은 넣지 않는다(프론트가 시각으로 계산) */
@Service
@Transactional(readOnly = true)
public class ConcertQueryService {

    private final ConcertRepository concertRepository;
    private final SeatGradeRepository seatGradeRepository;

    public ConcertQueryService(ConcertRepository concertRepository, SeatGradeRepository seatGradeRepository) {
        this.concertRepository = concertRepository;
        this.seatGradeRepository = seatGradeRepository;
    }

    public List<ConcertListDto> getConcertList(ConcertSearchCondition condition) {
        List<Concert> concerts = condition == null || condition.isEmpty()
                ? concertRepository.findAll()
                : concertRepository.search(condition);
        return concerts.stream().map(ConcertListDto::from).toList();
    }

    public ConcertDetailDto getConcertDetail(Long concertId) {
        Concert concert = concertRepository.findById(concertId)
                .orElseThrow(() -> new NotFoundException("공연을 찾을 수 없습니다"));
        List<SeatGradeDto> grades = seatGradeRepository.findByConcertId(concertId).stream()
                .map(grade -> new SeatGradeDto(grade.getId(), grade.getGradeName(), grade.getPrice()))
                .toList();
        return new ConcertDetailDto(concert.getId(), concert.getTitle(), concert.getEventOpen(),
                concert.getEventStart(), concert.getEventEnd(), concert.getVenue(), grades);
    }
}
