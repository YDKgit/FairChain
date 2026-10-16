package com.fairchain.example.service;

import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.example.config.ExampleProperties;
import com.fairchain.example.dto.ReallocationOfferDto;
import com.fairchain.example.entity.OfferStatus;
import com.fairchain.example.entity.ReallocationOffer;
import com.fairchain.example.entity.Seat;
import com.fairchain.example.entity.SeatStatus;
import com.fairchain.example.repository.ReallocationOfferRepository;
import com.fairchain.example.repository.SeatRepository;
import com.fairchain.example.repository.TicketRepository;
import com.fairchain.example.requester.ReservationAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * C-0307 재배정 서비스 — 매진 후 취소된 좌석을 원래 대기 순번대로 한 명씩 제안한다.
 * 통신은 예제서버 → FairChain 한 방향(명단 조회)뿐이고, 알림 · 기한 · 순서 진행은 예제서버가 담당한다.
 */
@Service
@Transactional
public class ReallocationService {

    private static final Logger log = LoggerFactory.getLogger(ReallocationService.class);
    private static final List<OfferStatus> IN_PROGRESS = List.of(OfferStatus.WAITING, OfferStatus.OFFERED);

    private final ReservationAccess reservationAccess;
    private final ReallocationOfferRepository offerRepository;
    private final TicketRepository ticketRepository;
    private final SeatRepository seatRepository;
    private final ExampleProperties properties;
    private final Clock clock;

    public ReallocationService(ReservationAccess reservationAccess, ReallocationOfferRepository offerRepository,
                               TicketRepository ticketRepository, SeatRepository seatRepository,
                               ExampleProperties properties, Clock clock) {
        this.reservationAccess = reservationAccess;
        this.offerRepository = offerRepository;
        this.ticketRepository = ticketRepository;
        this.seatRepository = seatRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 명단에서 이미 이 공연 티켓이 있거나 이 공연 제안을 받은 회원을 빼고 WAITING으로 저장 → 첫 사람에게 제안 */
    public void startReallocation(Long concertId, Long seatId) {
        ReallocationListDto candidates = reservationAccess.getReallocationCandidates(concertId);
        List<Long> userIds = candidates == null || candidates.userIds() == null ? List.of() : candidates.userIds();

        List<ReallocationOffer> offers = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < userIds.size(); i++) {
            Long memberId = userIds.get(i);
            if (!seen.add(memberId)
                    || ticketRepository.existsActiveByMemberIdAndConcertId(memberId, concertId)
                    || offerRepository.existsByConcertIdAndMemberId(concertId, memberId)) {
                continue;
            }
            // ReallocationListDto에 순번 필드가 없어 명단 순서(1부터)를 쓴다
            offers.add(new ReallocationOffer(concertId, seatId, memberId, (long) (i + 1)));
        }
        offerRepository.saveAll(offers);
        log.info("[재배정 시작] concertId={} seatId={} 후보 {}명", concertId, seatId, offers.size());
        notifyNext(concertId, seatId);
    }

    /** 다음 대기자에게 제안. 없으면 좌석을 일반 예매로 개방 */
    public void notifyNext(Long concertId, Long seatId) {
        Optional<ReallocationOffer> next = offerRepository.findNextWaiting(concertId, seatId);
        if (next.isPresent()) {
            ReallocationOffer offer = next.get();
            offer.offer(UUID.randomUUID().toString(), now().plus(properties.reallocation().offerTtl()));
            offerRepository.save(offer);
            // 앱 푸시 수단이 없어 로그 + GET /reallocation/{concertId}/offer 조회로 대신한다
            log.info("[재배정 알림] memberId={} concertId={} seatId={} 기한={}",
                    offer.getMemberId(), concertId, seatId, offer.getExpiresAt());
            return;
        }
        seatRepository.findByIdForUpdate(seatId)
                .filter(seat -> seat.getStatus() == SeatStatus.CANCELLED)
                .ifPresent(seat -> {
                    seat.open();
                    seatRepository.save(seat);
                    log.info("[재배정 종료] 후보 없음 → 좌석 개방 seatId={}", seatId);
                });
    }

    @Scheduled(fixedDelayString = "${example.reallocation.expire-interval-ms}")
    public void expireOffers() {
        for (ReallocationOffer offer : offerRepository.findExpired(now())) {
            offer.expire();
            offerRepository.save(offer);
            log.info("[재배정 만료] memberId={} seatId={}", offer.getMemberId(), offer.getSeatId());
            notifyNext(offer.getConcertId(), offer.getSeatId());
        }
    }

    @Transactional(readOnly = true)
    public boolean verifyOffer(Long concertId, Long seatId, Long memberId, String offerToken) {
        return findValidOffer(concertId, seatId, memberId, offerToken).isPresent();
    }

    /** 보완 — 유효한(OFFERED, 기한 안, 좌석 · 회원 · 공연 일치) 제안 */
    @Transactional(readOnly = true)
    public Optional<ReallocationOffer> findValidOffer(Long concertId, Long seatId, Long memberId, String offerToken) {
        return offerRepository.findByToken(offerToken)
                .filter(offer -> offer.isValidFor(concertId, seatId, memberId, now()));
    }

    public void acceptOffer(ReallocationOffer offer) {
        offer.accept();
        offerRepository.save(offer);
    }

    /** 보완 — 이 좌석 재배정이 진행 중인지 (재전송으로 같은 취소 결과가 다시 와도 중복 시작하지 않게) */
    @Transactional(readOnly = true)
    public boolean isInProgress(Long seatId) {
        return offerRepository.existsBySeatIdAndStatusIn(seatId, IN_PROGRESS);
    }

    /** 보완 — 내 OFFERED 제안 (재배정 알림 수신 대용) */
    @Transactional(readOnly = true)
    public Optional<ReallocationOfferDto> getMyOffer(Long memberId, Long concertId) {
        return offerRepository.findFirstByMemberIdAndConcertIdAndStatus(memberId, concertId, OfferStatus.OFFERED)
                .filter(offer -> offer.getExpiresAt().isAfter(now()))
                .map(offer -> new ReallocationOfferDto(offer.getId(), offer.getConcertId(), offer.getSeatId(),
                        seatRepository.findById(offer.getSeatId()).map(Seat::getSeatNumber).orElse(null),
                        offer.getOfferToken(), offer.getExpiresAt()));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
