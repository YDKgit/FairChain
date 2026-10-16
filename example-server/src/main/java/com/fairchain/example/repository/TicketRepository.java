package com.fairchain.example.repository;

import com.fairchain.example.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** C-0312 티켓 저장소 */
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    Optional<Ticket> findByQrToken(String qrToken);

    @Query("select t from Ticket t where t.memberId = :memberId order by t.ticketId")
    List<Ticket> findByMemberId(@Param("memberId") Long memberId);

    @Query("select t from Ticket t where t.memberId = :memberId and t.concertId = :concertId order by t.ticketId")
    List<Ticket> findByMemberIdAndConcertId(@Param("memberId") Long memberId, @Param("concertId") Long concertId);

    /** 1인 1매 확인 — 이 공연의 ACTIVE 티켓 보유 여부 */
    @Query("select count(t) > 0 from Ticket t where t.memberId = :memberId and t.concertId = :concertId "
            + "and t.status = com.fairchain.example.entity.TicketStatus.ACTIVE")
    boolean existsActiveByMemberIdAndConcertId(@Param("memberId") Long memberId, @Param("concertId") Long concertId);
}
