package com.fairchain.example.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReallocationOfferRepository extends JpaRepository<ReallocationOffer, Long> {

    Optional<ReallocationOffer> findFirstByRoundIdAndStatusOrderBySequenceNumberAsc(Long roundId, OfferStatus status);

    /** 순번이 가장 앞선 WAITING 제안 */
    default Optional<ReallocationOffer> findNextWaiting(Long roundId) {
        return findFirstByRoundIdAndStatusOrderBySequenceNumberAsc(roundId, OfferStatus.WAITING);
    }

    /** 지금 응답을 기다리는 제안 (회차당 하나) */
    default Optional<ReallocationOffer> findCurrent(Long roundId) {
        return findFirstByRoundIdAndStatusOrderBySequenceNumberAsc(roundId, OfferStatus.OFFERED);
    }

    List<ReallocationOffer> findByRoundIdAndStatus(Long roundId, OfferStatus status);

    List<ReallocationOffer> findByRoundIdOrderBySequenceNumberAsc(Long roundId);

    Optional<ReallocationOffer> findByOfferToken(String offerToken);

    List<ReallocationOffer> findByMemberIdAndStatusOrderByExpiresAtAsc(Long memberId, OfferStatus status);

    Optional<ReallocationOffer> findFirstByRoundIdAndMemberId(Long roundId, Long memberId);
}
