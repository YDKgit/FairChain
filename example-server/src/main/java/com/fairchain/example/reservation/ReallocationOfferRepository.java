package com.fairchain.example.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReallocationOfferRepository extends JpaRepository<ReallocationOffer, Long> {

    Optional<ReallocationOffer> findFirstByConcertIdAndSeatIdAndStatusOrderBySequenceNumberAsc(
            Long concertId, Long seatId, OfferStatus status);

    /** 순번이 가장 앞선 WAITING 제안 */
    default Optional<ReallocationOffer> findNextWaiting(Long concertId, Long seatId) {
        return findFirstByConcertIdAndSeatIdAndStatusOrderBySequenceNumberAsc(concertId, seatId, OfferStatus.WAITING);
    }

    List<ReallocationOffer> findByStatusAndExpiresAtBefore(OfferStatus status, LocalDateTime now);

    /** 기한이 지난 OFFERED 제안 */
    default List<ReallocationOffer> findExpired(LocalDateTime now) {
        return findByStatusAndExpiresAtBefore(OfferStatus.OFFERED, now);
    }

    Optional<ReallocationOffer> findByOfferToken(String offerToken);

    List<ReallocationOffer> findByMemberIdAndStatusOrderByExpiresAtAsc(Long memberId, OfferStatus status);
}
