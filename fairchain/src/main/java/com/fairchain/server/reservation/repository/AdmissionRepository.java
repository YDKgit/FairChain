package com.fairchain.server.reservation.repository;

import com.fairchain.server.reservation.entity.Admission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AdmissionRepository extends JpaRepository<Admission, Long> {

    /** 회원의 이 공연 최신 입장 기록 (재입장이면 행이 여러 개) */
    Optional<Admission> findTopByUserIdAndEventIdOrderByIdDesc(Long userId, Long eventId);

    /** 재배정 후보: 회원별 최신 입장 기록이 ADMITTED(입장했지만 미구매)인 것, 순번순 */
    @Query("""
            select a from Admission a
            where a.eventId = :eventId
              and a.status = com.fairchain.server.reservation.entity.AdmissionStatus.ADMITTED
              and a.id = (select max(b.id) from Admission b where b.eventId = a.eventId and b.userId = a.userId)
            order by a.sequenceNumber asc
            """)
    List<Admission> findNotPurchased(@Param("eventId") Long eventId);
}
