package com.fairchain.example.service;

import com.fairchain.api.dto.AnchorListResponseDto;
import com.fairchain.api.dto.BlockState;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifiedAnchorDto;
import com.fairchain.example.entity.RecordOutbox;
import com.fairchain.example.entity.Ticket;
import com.fairchain.example.entity.VerificationStatus;
import com.fairchain.example.support.ServiceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TicketVerificationServiceTest extends ServiceTestSupport {

    @Autowired
    private TicketVerificationService service;

    /** concertId=1, seatId=7, memberId=3, version=2 — DB에 저장하지 않은 티켓 */
    private Ticket ticket() {
        return new Ticket(3L, 7L, 1L, "qr", 2L, now());
    }

    private VerifiedAnchorDto anchorOf(Ticket ticket, BlockState blockState, boolean nodeMatched) {
        return new VerifiedAnchorDto(recordFactory.recordKey(ticket.getSeatId()), ticket.getVersion(),
                recordFactory.recordHash(ticket), 10L, blockState, nodeMatched);
    }

    @Test
    void 기록이_아직_Outbox에_PENDING이면_PENDING() {
        Ticket ticket = ticket();
        outboxRepository.save(new RecordOutbox(recordFactory.build(ticket, RecordType.PURCHASE), now()));
        assertEquals(VerificationStatus.PENDING, service.determineStatus(ticket, null));
    }

    @Test
    void anchor가_없으면_UNREGISTERED_LOCAL() {
        assertEquals(VerificationStatus.UNREGISTERED_LOCAL, service.determineStatus(ticket(), null));
    }

    @Test
    void 버전이_다르면_TAMPER_SUSPECTED() {
        Ticket ticket = ticket();
        VerifiedAnchorDto anchor = anchorOf(ticket, BlockState.CONFIRMED, true);
        VerifiedAnchorDto older = new VerifiedAnchorDto(anchor.recordKey(), 1L, anchor.recordHash(), 10L,
                BlockState.CONFIRMED, true);
        assertEquals(VerificationStatus.TAMPER_SUSPECTED, service.determineStatus(ticket, older));
    }

    @Test
    void 지문이_다르면_TAMPER_SUSPECTED() {
        Ticket ticket = ticket();
        VerifiedAnchorDto anchor = anchorOf(ticket, BlockState.CONFIRMED, true);
        VerifiedAnchorDto forged = new VerifiedAnchorDto(anchor.recordKey(), anchor.version(), "forged", 10L,
                BlockState.CONFIRMED, true);
        assertEquals(VerificationStatus.TAMPER_SUSPECTED, service.determineStatus(ticket, forged));
    }

    @Test
    void 블록_확정_전이면_PENDING() {
        Ticket ticket = ticket();
        assertEquals(VerificationStatus.PENDING,
                service.determineStatus(ticket, anchorOf(ticket, BlockState.PENDING, false)));
    }

    @Test
    void 노드_검증_불가면_UNAVAILABLE() {
        Ticket ticket = ticket();
        assertEquals(VerificationStatus.UNAVAILABLE,
                service.determineStatus(ticket, anchorOf(ticket, BlockState.CONFIRMED, true), false));
    }

    @Test
    void 노드가_일치하면_VERIFIED_아니면_TAMPER_SUSPECTED() {
        Ticket ticket = ticket();
        assertEquals(VerificationStatus.VERIFIED,
                service.determineStatus(ticket, anchorOf(ticket, BlockState.CONFIRMED, true)));
        assertEquals(VerificationStatus.TAMPER_SUSPECTED,
                service.determineStatus(ticket, anchorOf(ticket, BlockState.CONFIRMED, false)));
    }

    @Test
    void 로컬_티켓이_없는_anchor는_MISSING_LOCAL() {
        Ticket local = ticket();
        Ticket remoteOnly = new Ticket(3L, 8L, 1L, "qr2", 2L, now());
        AnchorListResponseDto response = new AnchorListResponseDto(List.of(
                anchorOf(local, BlockState.CONFIRMED, true),
                anchorOf(remoteOnly, BlockState.CONFIRMED, true)), true);

        List<TicketVerificationService.ComparedTicket> result = service.compare(List.of(local), response);

        assertEquals(2, result.size());
        assertEquals(VerificationStatus.VERIFIED, result.get(0).status());
        assertEquals(VerificationStatus.MISSING_LOCAL, result.get(1).status());
        assertNull(result.get(1).ticket());
    }
}
