package com.fairchain.example.repository;

import com.fairchain.example.dto.ConcertSearchCondition;
import com.fairchain.example.entity.Concert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** C-0707 콘서트 저장소 — 설계의 delete(id)는 deleteById(id) */
public interface ConcertRepository extends JpaRepository<Concert, Long> {

    /** 조건이 null이면 그 조건은 무시 — keyword(title LIKE) · venue(LIKE) · eventStart 날짜 범위 */
    default List<Concert> search(ConcertSearchCondition condition) {
        return searchBy(
                blankToNull(condition.keyword()),
                blankToNull(condition.venue()),
                condition.from() == null ? null : condition.from().atStartOfDay(),
                condition.to() == null ? null : condition.to().plusDays(1).atStartOfDay());
    }

    @Query("""
            select c from Concert c
            where (:keyword is null or c.title like concat('%', :keyword, '%'))
              and (:venue is null or c.venue like concat('%', :venue, '%'))
              and (:startFrom is null or c.eventStart >= :startFrom)
              and (:startBefore is null or c.eventStart < :startBefore)
            order by c.eventStart, c.id
            """)
    List<Concert> searchBy(@Param("keyword") String keyword,
                           @Param("venue") String venue,
                           @Param("startFrom") LocalDateTime startFrom,
                           @Param("startBefore") LocalDateTime startBefore);

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
