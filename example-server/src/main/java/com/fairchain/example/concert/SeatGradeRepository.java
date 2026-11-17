package com.fairchain.example.concert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public interface SeatGradeRepository extends JpaRepository<SeatGrade, Long> {

    List<SeatGrade> findByConcertId(Long concertId);

    /** 공연의 등급을 id로 찾기 쉽게 */
    default Map<Long, SeatGrade> mapByConcertId(Long concertId) {
        return findByConcertId(concertId).stream().collect(Collectors.toMap(SeatGrade::getId, Function.identity()));
    }

    void deleteByConcertId(Long concertId);
}
