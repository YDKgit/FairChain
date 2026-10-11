package com.fairchain.server.repository;
import com.fairchain.server.entity.TicketHead;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface TicketHeadRepository extends JpaRepository<TicketHead,Long> {
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select h from TicketHead h where h.clientId=:clientId and h.recordKey=:recordKey")
 Optional<TicketHead> lockTicket(@Param("clientId") String clientId,@Param("recordKey") String recordKey);
}
