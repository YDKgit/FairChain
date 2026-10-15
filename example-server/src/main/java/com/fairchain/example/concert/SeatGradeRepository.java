package com.fairchain.example.concert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatGradeRepository extends JpaRepository<SeatGrade, Long> {

    List<SeatGrade> findByConcertId(Long concertId);

    void deleteByConcertId(Long concertId);
}
