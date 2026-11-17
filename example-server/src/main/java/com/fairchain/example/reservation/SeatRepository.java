package com.fairchain.example.reservation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByConcertIdOrderByIdAsc(Long concertId);

    /** 같은 좌석에 동시에 들어온 예매 · 취소 · 입장은 한 번에 하나씩 (SELECT ... FOR UPDATE) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id);

    boolean existsByConcertIdAndSeatNumber(Long concertId, String seatNumber);

    long countByConcertId(Long concertId);

    long countByConcertIdAndStatus(Long concertId, SeatStatus status);

    List<Seat> findByConcertIdAndStatusOrderByIdAsc(Long concertId, SeatStatus status);

    /** 회차에 아직 묶이지 않은 보류 취소표 */
    List<Seat> findByConcertIdAndStatusAndReallocationRoundIdIsNullOrderByIdAsc(Long concertId, SeatStatus status);

    List<Seat> findByReallocationRoundIdAndStatusOrderByIdAsc(Long roundId, SeatStatus status);

    long countByReallocationRoundIdAndStatus(Long roundId, SeatStatus status);

    /** 이 상태의 좌석이 하나라도 있는 공연 */
    @Query("select distinct s.concertId from Seat s where s.status = :status")
    List<Long> findConcertIdsByStatus(@Param("status") SeatStatus status);
}
