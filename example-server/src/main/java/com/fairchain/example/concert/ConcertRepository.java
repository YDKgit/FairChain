package com.fairchain.example.concert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConcertRepository extends JpaRepository<Concert, Long> {

    /** 검색 조건 — 비어 있는 조건은 무시 */
    @Query("""
            select c from Concert c
            where (:keyword is null or lower(c.title) like lower(concat('%', :keyword, '%')))
              and (:venue is null or lower(c.venue) like lower(concat('%', :venue, '%')))
            order by c.eventStart
            """)
    List<Concert> search(@Param("keyword") String keyword, @Param("venue") String venue);
}
