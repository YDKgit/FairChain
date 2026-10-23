package com.fairchain.example.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecordOutboxRepository extends JpaRepository<RecordOutbox, Long> {

    List<RecordOutbox> findByStatusOrderByIdAsc(OutboxStatus status);

    /** 아직 전송되지 않은 기록 (저장 순서) */
    default List<RecordOutbox> findPending() {
        return findByStatusOrderByIdAsc(OutboxStatus.PENDING);
    }

    boolean existsBySeatIdAndStatus(Long seatId, OutboxStatus status);
}
