package com.fairchain.node.repository;
import com.fairchain.node.entity.PendingBlock;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PendingBlockRepository extends JpaRepository<PendingBlock,Long> {}
