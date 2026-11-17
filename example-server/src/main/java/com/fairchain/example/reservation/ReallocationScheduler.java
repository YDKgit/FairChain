package com.fairchain.example.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/** 주기 점검 — 보류 취소표가 있거나 회차가 열려 있는 공연을 돌며 재배정 일정을 한 단계씩 진행한다 */
@Component
public class ReallocationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReallocationScheduler.class);

    private final ReallocationService reallocationService;
    private final SeatRepository seatRepository;
    private final ReallocationRoundRepository roundRepository;

    public ReallocationScheduler(ReallocationService reallocationService, SeatRepository seatRepository,
                                 ReallocationRoundRepository roundRepository) {
        this.reallocationService = reallocationService;
        this.seatRepository = seatRepository;
        this.roundRepository = roundRepository;
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 10000)
    public void tick() {
        Set<Long> concertIds = new LinkedHashSet<>(seatRepository.findConcertIdsByStatus(SeatStatus.CANCELLED));
        roundRepository.findAllOpen().forEach(r -> concertIds.add(r.getConcertId()));
        for (Long concertId : concertIds) {
            try {
                reallocationService.advance(concertId);
            } catch (RuntimeException e) {
                log.warn("재배정 진행 실패 (공연 {}): {}", concertId, e.getMessage());
            }
        }
    }
}
