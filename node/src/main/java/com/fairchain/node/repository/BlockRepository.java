package com.fairchain.node.repository;
import com.fairchain.node.entity.Block;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface BlockRepository extends JpaRepository<Block,Long> {
 Optional<Block> findTopByOrderByBlockIndexDesc();
 Optional<Block> findTopByRecordKeyOrderByBlockIndexDesc(String recordKey);
}
