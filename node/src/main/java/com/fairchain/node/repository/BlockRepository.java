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
 Optional<Block> findTopByOrderByBlockIndexDesc();
 Optional<Block> findTopByRecordKeyOrderByBlockIndexDesc(String recordKey);
 /** 번호 순으로 한 묶음 — 체인 전체 검사 · 다른 노드 따라잡기용 */
 List<Block> findByBlockIndexGreaterThanEqualOrderByBlockIndexAsc(Long fromIndex,Pageable pageable);
 /** fromIndex번부터 끝까지 지움 — 잘못 받은 블록을 다시 받을 때 */
 @Transactional @Modifying(clearAutomatically=true,flushAutomatically=true)
 @Query("delete from Block b where b.blockIndex >= :fromIndex")
 int deleteByBlockIndexGreaterThanEqual(@Param("fromIndex") Long fromIndex);
}
