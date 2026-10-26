package com.fairchain.server.block.repository;

import com.fairchain.api.dto.BlockState;
import com.fairchain.server.block.entity.AnchorRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AnchorRecordRepository extends JpaRepository<AnchorRecord, Long> {

    Optional<AnchorRecord> findByRecordKeyAndVersion(String recordKey, Long version);

    Optional<AnchorRecord> findTopByRecordKeyOrderByVersionDesc(String recordKey);

    default Optional<AnchorRecord> findLatest(String recordKey) {
        return findTopByRecordKeyOrderByVersionDesc(recordKey);
    }

    List<AnchorRecord> findByBlockStateOrderByIdAsc(BlockState blockState);

    /** 노드 따라잡기: 확정된 마지막 블록 번호 (확정된 기록이 없으면 null) */
    @Query("select max(a.blockIndex) from AnchorRecord a where a.blockState = com.fairchain.api.dto.BlockState.CONFIRMED")
    Long findMaxConfirmedBlockIndex();

    /** 노드 따라잡기: 블록 번호 구간의 확정 기록 (번호 순) */
    List<AnchorRecord> findByBlockStateAndBlockIndexBetweenOrderByBlockIndexAsc(BlockState blockState, Long from, Long to);

    /** 시연 페이지: 한 공연의 모든 기록 (오래된 순) */
    List<AnchorRecord> findByClientIdAndEventIdOrderByIdAsc(String clientId, Long eventId);

    /**
     * SS-05 목록: 좌석 키마다 최신 버전만, 그 최신 기록의 주인이 이 회원이고 PURCHASE · CHECKIN인 것
     * (취소 · 재배정으로 주인이 바뀐 티켓 제외). eventId가 null이면 모든 공연.
     */
    @Query("""
            select a from AnchorRecord a
            where a.clientId = :clientId
              and a.userId = :userId
              and (:eventId is null or a.eventId = :eventId)
              and a.type in (com.fairchain.api.dto.RecordType.PURCHASE, com.fairchain.api.dto.RecordType.CHECKIN)
              and a.version = (select max(b.version) from AnchorRecord b where b.recordKey = a.recordKey)
            order by a.eventId, a.id
            """)
    List<AnchorRecord> findLatestOwned(@Param("clientId") String clientId,
                                       @Param("userId") Long userId,
                                       @Param("eventId") Long eventId);
}
