package com.fairchain.node.repository;
import com.fairchain.node.entity.Block;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface BlockRepository extends JpaRepository<Block,Long> {
 Optional<Block> findTopByOrderByBlockIndexDesc();
 Optional<Block> findTopByRecordKeyOrderByBlockIndexDesc(String recordKey);
 /** 번호 순으로 한 묶음 — 체인 전체 검사 · 다른 노드 따라잡기용 */
 List<Block> findByBlockIndexGreaterThanEqualOrderByBlockIndexAsc(Long fromIndex,Pageable pageable);
}
