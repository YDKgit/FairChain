package com.fairchain.example.reservation;

import com.fairchain.example.common.ApiException;
import com.fairchain.example.concert.Concert;
import com.fairchain.example.concert.ConcertRepository;
import com.fairchain.example.reservation.ReallocationDtos.RoundDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 매진 후 취소표 재배정 (C-0307) — 상태를 바꾸는 쪽. 조회는 ReallocationQueryService, 주기 점검은 ReallocationScheduler.
 *
 * <pre>
 * 취소 → 좌석 보류(CANCELLED)
 * 매일 offerTime(20:00) → 그때까지 보류된 취소표를 한 회차로 묶고, FairChain 명단(최초 대기 순번순)에
 *                         한 사람씩 "남은 취소표 중 하나를 고르세요"를 offerTtl 동안 제안
 *                         (offerTime 이후 취소표는 다음 날 회차)
 * 명단을 다 돌고도 남으면 → 다음 날 releaseTime(09:00)에 일반 판매로
 * 공연 시작까지 finalWindow(24h) 이하 → 명단을 돌리지 않고 보류분까지 모두 즉시 일반 판매로
 * </pre>
 *
 * 명단은 FairChain이 만들고, 제안 · 기한 · 다음 사람으로 넘기기 · 일정은 예제서버가 한다.
 */
@Service
public class ReallocationService {

    private static final Logger log = LoggerFactory.getLogger(ReallocationService.class);
    private static final Set<TicketStatus> HOLDING = Set.of(TicketStatus.ACTIVE, TicketStatus.CHECKED_IN);

    private final ReallocationOfferRepository offerRepository;
    private final ReallocationRoundRepository roundRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final ConcertRepository concertRepository;
    private final ReservationAccess reservationAccess;
    private final ReallocationSchedule schedule;
    private final ReallocationQueryService queryService;
    /** 같은 클래스 안에서 advance를 부를 때도 트랜잭션이 걸리도록 (프록시를 거치지 않으므로) */
    private final TransactionTemplate transactionTemplate;

    public ReallocationService(ReallocationOfferRepository offerRepository, ReallocationRoundRepository roundRepository,
                               SeatRepository seatRepository, TicketRepository ticketRepository,
                               ConcertRepository concertRepository, ReservationAccess reservationAccess,
                               ReallocationSchedule schedule, ReallocationQueryService queryService,
                               PlatformTransactionManager transactionManager) {
        this.offerRepository = offerRepository;
        this.roundRepository = roundRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.concertRepository = concertRepository;
        this.reservationAccess = reservationAccess;
        this.schedule = schedule;
        this.queryService = queryService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 취소 직후

    /** 취소 기록이 FairChain에 들어간 뒤: 매진 전이거나 공연 임박이면 바로 판매, 아니면 다음 제안 시각까지 보류 */
    @Transactional
    public void afterCancel(Long concertId, Long seatId, boolean soldOut) {
        Concert concert = concertRepository.findById(concertId).orElse(null);
        boolean releaseNow = !soldOut || concert == null || schedule.isFinalWindow(concert, LocalDateTime.now());
        if (releaseNow) {
            seatRepository.findByIdForUpdate(seatId).ifPresent(Seat::reopen);
            log.info("취소표 즉시 판매: 공연 {} 좌석 {} ({})", concertId, seatId, soldOut ? "공연 임박" : "매진 전");
        } else {
            log.info("취소표 보류: 공연 {} 좌석 {} — 다음 제안 시각 {}", concertId, seatId,
                    schedule.nextOfferAt(concertId, LocalDateTime.now()));
        }
    }

    // ------------------------------------------------------------------ 진행

    /** 한 공연의 재배정을 지금 시각 기준으로 한 단계 진행한다 (예매 완료 · 기한 초과 · 일정 도달) */
    public void advance(Long concertId) {
        transactionTemplate.executeWithoutResult(status -> doAdvance(concertId));
    }

    private void doAdvance(Long concertId) {
        Concert concert = concertRepository.findById(concertId).orElse(null);
        if (concert == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (schedule.isFinalWindow(concert, now)) {
            releaseAll(concertId, "공연 임박");
            return;
        }
        Optional<ReallocationRound> open = roundRepository.findOpen(concertId);
        if (open.isPresent()) {
            progress(open.get(), now);
            return;
        }
        if (schedule.dueForOffer(concertId, now) && !heldSeatsNotInRound(concertId).isEmpty()) {
            startRound(concertId, now);
        }
    }

    /** 예매가 끝난 뒤 — 취소표가 남아 있으면 바로 다음 사람에게 제안 */
    public void afterAccept(Long concertId) {
        try {
            advance(concertId);
        } catch (RuntimeException e) {
            log.warn("재배정 다음 제안 실패 (공연 {}): {}", concertId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 운영자 수동 진행 (시연용)

    /** 제안 시각을 기다리지 않고 지금 회차를 시작한다 */
    @Transactional
    public RoundDto offerNow(Long concertId) {
        Concert concert = concertRepository.findById(concertId).orElseThrow(() -> ApiException.notFound("공연이 없습니다"));
        LocalDateTime now = LocalDateTime.now();
        if (schedule.isFinalWindow(concert, now)) {
            throw ApiException.conflict("공연 임박 — 명단을 돌리지 않고 취소표를 바로 판매합니다");
        }
        if (roundRepository.findOpen(concertId).isPresent()) {
            throw ApiException.conflict("이미 진행 중인 회차가 있습니다");
        }
        if (heldSeatsNotInRound(concertId).isEmpty()) {
            throw ApiException.conflict("보류 중인 취소표가 없습니다");
        }
        ReallocationRound round = startRound(concertId, now);
        if (round == null) {
            throw ApiException.conflict("FairChain에서 재배정 명단을 받지 못했습니다");
        }
        return queryService.roundDto(round);
    }

    /** 남은 취소표를 지금 모두 일반 판매로 푼다 */
    @Transactional
    public int releaseNow(Long concertId) {
        concertRepository.findById(concertId).orElseThrow(() -> ApiException.notFound("공연이 없습니다"));
        return releaseAll(concertId, "운영자 수동");
    }

    // ------------------------------------------------------------------ 회차 진행

    /** 보류 중인데 아직 회차에 묶이지 않은 취소표 */
    private List<Seat> heldSeatsNotInRound(Long concertId) {
        return seatRepository.findByConcertIdAndStatusAndReallocationRoundIdIsNullOrderByIdAsc(concertId, SeatStatus.CANCELLED);
    }

    /** FairChain 명단을 받아 회차를 열고 첫 사람에게 제안. 명단을 못 받으면 null (다음 점검 때 다시) */
    private ReallocationRound startRound(Long concertId, LocalDateTime now) {
        List<Seat> held = heldSeatsNotInRound(concertId);
        List<Long> candidates;
        try {
            candidates = reservationAccess.getReallocationCandidates(concertId).userIds().stream()
                    .filter(memberId -> !ticketRepository.existsByMemberIdAndConcertIdAndStatusIn(memberId, concertId, HOLDING))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("재배정 명단 조회 실패 (공연 {}): {} — 다음 점검 때 다시", concertId, e.getMessage());
            return null;
        }
        ReallocationRound round = roundRepository.save(new ReallocationRound(concertId, candidates.size(), held.size()));
        held.forEach(seat -> seat.joinRound(round.getId()));
        long order = 0;
        for (Long memberId : candidates) {
            offerRepository.save(new ReallocationOffer(round.getId(), concertId, memberId, ++order));
        }
        log.info("재배정 회차 {} 시작: 공연 {} — 취소표 {}장, 명단 {}명", round.getId(), concertId, held.size(), candidates.size());
        progress(round, now);
        return round;
    }

    /** 회차를 한 단계 진행: 기한 지난 제안 정리 → 다음 사람에게 제안 → 명단이 끝나면 일반 판매 예약 */
    private void progress(ReallocationRound round, LocalDateTime now) {
        if (round.getStatus() == ReallocationRound.Status.RELEASE_PENDING) {
            if (!now.isBefore(round.getReleaseAt())) {
                releaseRound(round, "일반 판매 시각 도달");
            }
            return;
        }
        if (seatRepository.countByReallocationRoundIdAndStatus(round.getId(), SeatStatus.CANCELLED) == 0) {
            finishRound(round, "취소표가 모두 나감");
            return;
        }
        Optional<ReallocationOffer> current = offerRepository.findCurrent(round.getId());
        if (current.isPresent()) {
            if (now.isBefore(current.get().getExpiresAt())) {
                return;             // 아직 응답을 기다리는 중
            }
            current.get().expire();
            log.info("재배정 제안 기한 초과: 회차 {} 순번 {}", round.getId(), current.get().getSequenceNumber());
        }
        Optional<ReallocationOffer> next = offerRepository.findNextWaiting(round.getId());
        if (next.isPresent()) {
            next.get().offer(schedule.offerTtl());
            log.info("재배정 제안: 회차 {} 순번 {} 회원 {} (기한 {})", round.getId(), next.get().getSequenceNumber(),
                    next.get().getMemberId(), next.get().getExpiresAt());
            return;
        }
        LocalDateTime releaseAt = schedule.releaseAt(now);
        round.scheduleRelease(releaseAt);
        log.info("재배정 명단 소진: 회차 {} — 남은 취소표는 {}에 일반 판매", round.getId(), releaseAt);
    }

    private void releaseRound(ReallocationRound round, String reason) {
        List<Seat> remaining = seatRepository.findByReallocationRoundIdAndStatusOrderByIdAsc(round.getId(), SeatStatus.CANCELLED);
        remaining.forEach(Seat::reopen);
        finishRound(round, reason + " — " + remaining.size() + "장 일반 판매");
    }

    private void finishRound(ReallocationRound round, String reason) {
        offerRepository.findByRoundIdAndStatus(round.getId(), OfferStatus.OFFERED).forEach(ReallocationOffer::expire);
        offerRepository.findByRoundIdAndStatus(round.getId(), OfferStatus.WAITING).forEach(ReallocationOffer::skip);
        round.finish();
        log.info("재배정 회차 {} 종료: {}", round.getId(), reason);
    }

    /** 보류분 · 회차분 가리지 않고 이 공연의 취소표를 전부 일반 판매로 */
    private int releaseAll(Long concertId, String reason) {
        List<Seat> held = seatRepository.findByConcertIdAndStatusOrderByIdAsc(concertId, SeatStatus.CANCELLED);
        held.forEach(Seat::reopen);
        roundRepository.findOpen(concertId).ifPresent(round -> finishRound(round, reason));
        if (!held.isEmpty()) {
            log.info("취소표 {}장 일반 판매 ({}): 공연 {}", held.size(), reason, concertId);
        }
        return held.size();
    }

    // ------------------------------------------------------------------ 수락 (ReservationService가 예매 트랜잭션 안에서 호출)

    /** 제안 토큰 · 공연 · 회원 · 기한 일치 확인 */
    @Transactional(readOnly = true)
    public ReallocationOffer verifyOffer(Long concertId, Long memberId, String offerToken) {
        return offerRepository.findByOfferToken(offerToken)
                .filter(o -> o.getConcertId().equals(concertId))
                .filter(o -> o.isUsableBy(memberId, LocalDateTime.now()))
                .orElseThrow(() -> ApiException.conflict("유효한 재배정 제안이 아닙니다 (기한 초과 또는 다른 회원의 제안)"));
    }

    /** 제안으로 고를 수 있는 좌석인지 — 같은 회차에 묶인 보류 취소표만 */
    public boolean canTake(ReallocationOffer offer, Seat seat) {
        return seat.getStatus() == SeatStatus.CANCELLED && offer.getRoundId().equals(seat.getReallocationRoundId());
    }

    public void acceptOffer(ReallocationOffer offer, Long seatId) {
        offer.accept(seatId);
        offerRepository.save(offer);
    }
}
