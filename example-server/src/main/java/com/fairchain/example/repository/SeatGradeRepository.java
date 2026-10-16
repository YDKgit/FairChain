package com.fairchain.example.repository;

import com.fairchain.example.entity.SeatGrade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** C-0708 좌석 등급 저장소 */
public interface SeatGradeRepository extends JpaRepository<SeatGrade, Long> {

    List<SeatGrade> findByConcertId(Long concertId);
}
