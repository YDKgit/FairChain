package com.fairchain.example.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReallocationRoundRepository extends JpaRepository<ReallocationRound, Long> {

    Optional<ReallocationRound> findFirstByConcertIdAndStatusNotOrderByIdDesc(Long concertId, ReallocationRound.Status status);

    /** 아직 끝나지 않은 회차 (공연당 하나) */
    default Optional<ReallocationRound> findOpen(Long concertId) {
        return findFirstByConcertIdAndStatusNotOrderByIdDesc(concertId, ReallocationRound.Status.DONE);
    }

    Optional<ReallocationRound> findFirstByConcertIdOrderByIdDesc(Long concertId);

    List<ReallocationRound> findByStatusNot(ReallocationRound.Status status);

    /** 끝나지 않은 회차 전부 */
    default List<ReallocationRound> findAllOpen() {
        return findByStatusNot(ReallocationRound.Status.DONE);
    }

    List<ReallocationRound> findByConcertIdOrderByIdDesc(Long concertId);
}
