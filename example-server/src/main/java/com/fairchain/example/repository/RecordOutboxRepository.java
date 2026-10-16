package com.fairchain.example.repository;

import com.fairchain.example.entity.OutboxStatus;
import com.fairchain.example.entity.RecordOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/** C-0314 기록 전송 대기열 저장소 */
public interface RecordOutboxRepository extends JpaRepository<RecordOutbox, Long> {

    /** 재전송 대상 — createdBefore 이전에 만들어진 PENDING, id 순 */
    default List<RecordOutbox> findPending(LocalDateTime createdBefore) {
        return findByStatusAndCreatedAtBeforeOrderByIdAsc(OutboxStatus.PENDING, createdBefore);
    }

    /** 보완 — 이 기록(좌석 키 · 버전)이 아직 전송 전인지. json 검색 대신 PENDING 목록을 읽어 비교 */
    default boolean existsPendingFor(String recordKey, Long version) {
        return findByStatus(OutboxStatus.PENDING).stream().anyMatch(outbox -> outbox.isFor(recordKey, version));
    }

    List<RecordOutbox> findByStatus(OutboxStatus status);

    List<RecordOutbox> findByStatusAndCreatedAtBeforeOrderByIdAsc(OutboxStatus status, LocalDateTime createdBefore);
}
