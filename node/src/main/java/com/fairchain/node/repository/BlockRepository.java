package com.fairchain.node.repository;
import com.fairchain.node.entity.Block;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
public interface BlockRepository extends JpaRepository<Block,Long> {
 /**
  * 마지막 블록 번호. MAX는 기본 키 인덱스 끝에서 바로 읽는다 (H2: direct lookup).
  * "ORDER BY blockIndex DESC LIMIT 1"(findTopBy…Desc)은 H2가 거꾸로 훑지 못해 장부 전체를 읽어 정렬한다 —
  * 블록 1만 개에서 한 번에 수백 ms, 블록을 붙일 때마다 여러 번 불려 장부가 길수록 기록이 느려졌다 (2026-11-26)
  */
 @Query("select max(b.blockIndex) from Block b")
 Long maxBlockIndex();
 Optional<Block> findTopByRecordKeyOrderByBlockIndexDesc(String recordKey);
 /** 번호 순으로 한 묶음 — 체인 전체 검사 · 다른 노드 따라잡기용 */
 List<Block> findByBlockIndexGreaterThanEqualOrderByBlockIndexAsc(Long fromIndex,Pageable pageable);
 /** fromIndex번부터 끝까지 지움 — 잘못 받은 블록을 다시 받을 때 */
 @Transactional @Modifying(clearAutomatically=true,flushAutomatically=true)
 @Query("delete from Block b where b.blockIndex >= :fromIndex")
 int deleteByBlockIndexGreaterThanEqual(@Param("fromIndex") Long fromIndex);
}
