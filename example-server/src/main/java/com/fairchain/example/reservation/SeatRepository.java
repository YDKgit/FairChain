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
}
