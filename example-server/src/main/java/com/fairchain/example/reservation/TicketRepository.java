package com.fairchain.example.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    Optional<Ticket> findByQrToken(String qrToken);

    List<Ticket> findByMemberIdOrderByTicketIdDesc(Long memberId);

    List<Ticket> findByMemberIdAndConcertIdOrderByTicketIdDesc(Long memberId, Long concertId);

    /** 1인 1매 — 같은 공연에 쓸 수 있는 티켓을 이미 가지고 있는지 */
    boolean existsByMemberIdAndConcertIdAndStatusIn(Long memberId, Long concertId, Collection<TicketStatus> statuses);

    boolean existsByConcertId(Long concertId);
}
