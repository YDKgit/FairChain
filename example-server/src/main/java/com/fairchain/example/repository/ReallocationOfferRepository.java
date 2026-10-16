package com.fairchain.example.repository;

import com.fairchain.example.entity.OfferStatus;
import com.fairchain.example.entity.ReallocationOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** C-0313 재배정 제안 저장소 */
public interface ReallocationOfferRepository extends JpaRepository<ReallocationOffer, Long> {

    /** 알림 대기 중 대기 순번이 가장 빠른 1건 */
    default Optional<ReallocationOffer> findNextWaiting(Long concertId, Long seatId) {
        return findFirstByConcertIdAndSeatIdAndStatusOrderBySequenceNumberAsc(concertId, seatId, OfferStatus.WAITING);
    }

    /** 응답 기한이 지난 OFFERED */
    default List<ReallocationOffer> findExpired(LocalDateTime now) {
        return findByStatusAndExpiresAtBefore(OfferStatus.OFFERED, now);
    }

    @Query("select o from ReallocationOffer o where o.offerToken = :offerToken")
    Optional<ReallocationOffer> findByToken(@Param("offerToken") String offerToken);

    Optional<ReallocationOffer> findFirstByConcertIdAndSeatIdAndStatusOrderBySequenceNumberAsc(
            Long concertId, Long seatId, OfferStatus status);

    List<ReallocationOffer> findByStatusAndExpiresAtBefore(OfferStatus status, LocalDateTime now);

    /** 보완 — 이 공연 제안을 이미 받은 회원인지 */
    boolean existsByConcertIdAndMemberId(Long concertId, Long memberId);

    /** 보완 — 이 좌석의 재배정이 진행 중인지 (WAITING · OFFERED) */
    boolean existsBySeatIdAndStatusIn(Long seatId, Collection<OfferStatus> statuses);

    /** 보완 — 내 제안 조회 */
    Optional<ReallocationOffer> findFirstByMemberIdAndConcertIdAndStatus(Long memberId, Long concertId, OfferStatus status);
}
