package com.fairchain.example.repository;

import com.fairchain.example.entity.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** C-0311 좌석 저장소 — seats에는 concert_id가 없어 seat_grades를 거쳐 찾는다 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Query("select s from Seat s where s.seatGradeId in "
            + "(select g.id from SeatGrade g where g.concertId = :concertId) order by s.id")
    List<Seat> findByConcertId(@Param("concertId") Long concertId);

    /** 좌석 잠금 (SELECT ... FOR UPDATE) — 트랜잭션 안에서만 호출 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id);
}
