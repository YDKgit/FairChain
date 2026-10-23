package com.fairchain.example.reservation;

import com.fairchain.example.common.ApiException;
import com.fairchain.example.common.ExampleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 매진 후 취소표 재배정 (C-0307). 명단은 FairChain이 최초 대기 순번순으로 만들고,
 * 알림 · 제한시간 · 다음 사람으로 넘기기는 예제서버가 한다.
 */
@Service
public class ReallocationService {

    private static final Logger log = LoggerFactory.getLogger(ReallocationService.class);
    private static final Set<TicketStatus> HOLDING = Set.of(TicketStatus.ACTIVE, TicketStatus.CHECKED_IN);

    private final ReallocationOfferRepository offerRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final ReservationAccess reservationAccess;
    private final Duration offerTtl;

    public ReallocationService(ReallocationOfferRepository offerRepository, SeatRepository seatRepository,
                               TicketRepository ticketRepository, ReservationAccess reservationAccess,
                               ExampleProperties properties) {
        this.offerRepository = offerRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.reservationAccess = reservationAccess;
        this.offerTtl = properties.offerTtl();
    }

    /** 취소 기록이 FairChain에 들어간 뒤: 매진 후 취소면 재배정, 아니면 좌석을 다시 판매 */
    @Transactional
    public void afterCancel(Long concertId, Long seatId, boolean soldOut) {
        if (soldOut) {
            startReallocation(concertId, seatId);
        } else {
            seatRepository.findByIdForUpdate(seatId).ifPresent(Seat::reopen);
        }
    }

    /** FairChain 명단을 받아 순서대로 WAITING 제안을 만들고 첫 사람에게 알린다 */
    @Transactional
    public void startReallocation(Long concertId, Long seatId) {
        List<Long> candidates = reservationAccess.getReallocationCandidates(concertId).userIds();
        long order = 0;
        for (Long memberId : candidates) {
            if (!ticketRepository.existsByMemberIdAndConcertIdAndStatusIn(memberId, concertId, HOLDING)) {
                offerRepository.save(new ReallocationOffer(concertId, seatId, memberId, ++order));
            }
        }
        log.info("재배정 시작: 공연 {} 좌석 {} — 후보 {}명", concertId, seatId, order);
        notifyNext(concertId, seatId);
    }

    /** 다음 대기자에게 제한시간 제안. 더 없으면 좌석을 일반 판매로 돌린다 */
    @Transactional
    public void notifyNext(Long concertId, Long seatId) {
        offerRepository.findNextWaiting(concertId, seatId).ifPresentOrElse(
                offer -> offer.offer(offerTtl),
                () -> seatRepository.findByIdForUpdate(seatId).ifPresent(seat -> {
                    if (seat.getStatus() == SeatStatus.CANCELLED) {
                        seat.reopen();
                    }
                }));
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    @Transactional
    public void expireOffers() {
        for (ReallocationOffer offer : offerRepository.findExpired(LocalDateTime.now())) {
            offer.expire();
            notifyNext(offer.getConcertId(), offer.getSeatId());
        }
    }

    /** 제안 토큰 · 좌석 · 회원 · 기한 일치 확인 */
    @Transactional(readOnly = true)
    public ReallocationOffer verifyOffer(Long concertId, Long seatId, Long memberId, String offerToken) {
        return offerRepository.findByOfferToken(offerToken)
                .filter(o -> o.getConcertId().equals(concertId) && o.getSeatId().equals(seatId))
                .filter(o -> o.isUsableBy(memberId, LocalDateTime.now()))
                .orElseThrow(() -> ApiException.conflict("유효한 재배정 제안이 아닙니다 (기한 초과 또는 다른 회원의 제안)"));
    }

    public void acceptOffer(ReallocationOffer offer) {
        offer.accept();
        offerRepository.save(offer);
    }

    @Transactional(readOnly = true)
    public List<ReallocationOffer> openOffers(Long memberId) {
        return offerRepository.findByMemberIdAndStatusOrderByExpiresAtAsc(memberId, OfferStatus.OFFERED);
    }
}
