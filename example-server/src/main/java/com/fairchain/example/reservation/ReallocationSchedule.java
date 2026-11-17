package com.fairchain.example.reservation;

import com.fairchain.example.common.ExampleProperties;
import com.fairchain.example.concert.Concert;
import com.fairchain.example.reservation.ReallocationDtos.ScheduleDto;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 재배정 일정 계산 — "지금 제안할 때인가", "다음 제안은 언제인가", "공연 임박인가".
 * 설정값(offerTime · releaseTime · offerTtl · finalWindow)을 아는 곳은 여기뿐이다.
 */
@Component
public class ReallocationSchedule {

    private final ExampleProperties.Reallocation schedule;
    private final ReallocationRoundRepository roundRepository;

    public ReallocationSchedule(ExampleProperties properties, ReallocationRoundRepository roundRepository) {
        this.schedule = properties.reallocation();
        this.roundRepository = roundRepository;
    }

    /** 공연 시작까지 finalWindow 이하로 남음 → 명단을 돌리지 않고 취소표를 바로 판매 */
    public boolean isFinalWindow(Concert concert, LocalDateTime now) {
        return !now.isBefore(concert.getEventStart().minus(schedule.finalWindow()));
    }

    /** 오늘 제안 시각이 지났고 오늘 연 회차가 없으면 지금 제안할 때 */
    public boolean dueForOffer(Long concertId, LocalDateTime now) {
        if (now.toLocalTime().isBefore(schedule.offerTime())) {
            return false;
        }
        return roundRepository.findFirstByConcertIdOrderByIdDesc(concertId)
                .map(last -> last.getStartedAt().toLocalDate().isBefore(now.toLocalDate()))
                .orElse(true);
    }

    /** 다음 제안 시작 예정 시각 — 오늘 아직 안 돌렸고 제안 시각 전이면 오늘, 아니면 내일 */
    public LocalDateTime nextOfferAt(Long concertId, LocalDateTime now) {
        LocalDateTime today = now.toLocalDate().atTime(schedule.offerTime());
        boolean ranToday = roundRepository.findFirstByConcertIdOrderByIdDesc(concertId)
                .map(last -> last.getStartedAt().toLocalDate().equals(now.toLocalDate()))
                .orElse(false);
        return (now.isBefore(today) && !ranToday) ? today : today.plusDays(1);
    }

    /** 명단을 다 돌고 남은 취소표를 일반 판매로 푸는 시각 — 다음 날 releaseTime */
    public LocalDateTime releaseAt(LocalDateTime now) {
        return now.toLocalDate().plusDays(1).atTime(schedule.releaseTime());
    }

    /** 한 사람에게 주는 응답 기한 */
    public Duration offerTtl() {
        return schedule.offerTtl();
    }

    public ScheduleDto toDto() {
        return new ScheduleDto(schedule.offerTime(), schedule.releaseTime(), schedule.offerTtl(), schedule.finalWindow());
    }
}
