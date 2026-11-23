package com.fairchain.example.reservation;

import com.fairchain.example.reservation.ReservationDtos.SeatDto;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** 취소표 재배정 DTO (CD-03-2) */
public final class ReallocationDtos {

    private ReallocationDtos() {}

    /** 나에게 온 제안 — 기한 안에 seats 중 하나를 골라 offerToken으로 예매한다 */
    public record OfferDto(Long offerId, Long concertId, String concertTitle, String offerToken,
                           LocalDateTime offeredAt, LocalDateTime expiresAt, List<SeatDto> seats) {}

    /**
     * 공연의 재배정 현황 (회원 · 비회원 공용).
     *
     * @param heldCount        지금 보류 중인 취소표 수 (회차에 묶인 것 포함)
     * @param roundStatus      진행 중인 회차 상태 (없으면 null)
     * @param currentSequence  지금 제안을 받고 있는 사람의 명단 순번 (없으면 null)
     * @param nextOfferAt      다음 제안 시작 예정 시각 (보류 취소표가 있을 때)
     * @param releaseAt        남은 취소표가 일반 판매로 풀리는 예정 시각
     * @param finalWindow      공연 임박 — 명단을 돌리지 않고 취소표를 바로 판매
     * @param myRank           로그인한 회원의 명단 순번 = 취소표를 제안받을 차례 (명단에 없거나 비로그인이면 null)
     * @param myQueueNumber    그 회원이 대기열에 진입한 순번 (앞사람이 예매에 성공해 명단에서 빠지면 myRank보다 크다)
     * @param myOffer          로그인한 회원에게 지금 와 있는 제안
     */
    public record StatusDto(Long concertId, long heldCount, ReallocationRound.Status roundStatus, Integer candidateCount,
                            Long currentSequence, LocalDateTime nextOfferAt, LocalDateTime releaseAt, boolean finalWindow,
                            Long myRank, Long myQueueNumber, OfferStatus myOfferStatus, OfferDto myOffer) {}

    /** 운영자 화면용 — 보류 취소표, 진행 중인 회차와 제안 목록, 일정 */
    public record AdminStatusDto(Long concertId, String concertTitle, boolean finalWindow, LocalDateTime nextOfferAt,
                                 ScheduleDto schedule, List<HeldSeatDto> heldSeats, RoundDto round, List<RoundDto> history) {}

    public record ScheduleDto(LocalTime offerTime, LocalTime releaseTime, Duration offerTtl, Duration finalWindow) {}

    public record HeldSeatDto(Long seatId, String seatNumber, String gradeName, LocalDateTime cancelledAt, boolean inRound) {}

    public record RoundDto(Long roundId, ReallocationRound.Status status, LocalDateTime startedAt, LocalDateTime releaseAt,
                           LocalDateTime finishedAt, int candidateCount, int seatCount, long remainingSeats,
                           List<RoundOfferDto> offers) {}

    public record RoundOfferDto(Long sequenceNumber, Long memberId, String memberName, OfferStatus status,
                                LocalDateTime offeredAt, LocalDateTime expiresAt, String seatNumber) {}
}
