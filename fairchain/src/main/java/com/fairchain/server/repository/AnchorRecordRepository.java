package com.fairchain.server.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fairchain.server.entity.AnchorRecord;
import java.util.Optional;
public interface AnchorRecordRepository extends JpaRepository<AnchorRecord,Long> {
 Optional<AnchorRecord> findTopByRecordKeyOrderByVersionDesc(String recordKey);
 Optional<AnchorRecord> findTopByClientServerIdAndRecordKeyOrderByVersionDesc(String clientServerId,String recordKey);
}
