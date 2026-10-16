package com.fairchain.example.service;

import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.dto.VerifyStatus;
import com.fairchain.example.common.ConflictException;
import com.fairchain.example.common.FairChainException;
import com.fairchain.example.common.UnauthorizedException;
import com.fairchain.example.dto.CheckinResultDto;
import com.fairchain.example.dto.GateStaffRegisterDto;
import com.fairchain.example.dto.LoginRequestDto;
import com.fairchain.example.entity.Concert;
import com.fairchain.example.entity.OutboxStatus;
import com.fairchain.example.entity.RecordOutbox;
import com.fairchain.example.entity.Seat;
import com.fairchain.example.entity.Ticket;
import com.fairchain.example.entity.TicketStatus;
import com.fairchain.example.support.ServiceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckinServiceTest extends ServiceTestSupport {

    @Autowired
    private CheckinService checkinService;

    private Ticket ticketFor(Concert concert) {
        return activeTicket(member("user1"), concert, seat(grade(concert), "A-1"));
    }

    private void verified(VerifyStatus status) {
        when(checkinAccess.verify(any())).thenReturn(new VerifyResultDto(status, null));
    }

    @Test
    void 없는_QR이면_거부() {
        assertEquals(CheckinService.INVALID_QR, checkinService.scanQr("nope").reason());
    }

    @Test
    void 취소된_티켓과_이미_입장한_티켓은_거부() {
        Concert concert = openConcert();
        Ticket ticket = ticketFor(concert);
        ticket.cancel(3L);
        ticketRepository.save(ticket);
        assertEquals(CheckinService.CANCELLED_TICKET, checkinService.scanQr(ticket.getQrToken()).reason());

        Ticket checkedIn = activeTicket(member("user2"), concert, seat(grade(concert), "A-2"));
        checkedIn.checkIn(now(), 3L);
        ticketRepository.save(checkedIn);
        assertEquals(CheckinService.ALREADY_CHECKED_IN, checkinService.scanQr(checkedIn.getQrToken()).reason());
    }

    @Test
    void 공연_기간_밖이면_거부() {
        Ticket ticket = ticketFor(futureConcert());
        verified(VerifyStatus.VERIFIED);
        assertEquals(CheckinService.OUT_OF_PERIOD, checkinService.scanQr(ticket.getQrToken()).reason());
        verify(checkinAccess, never()).verify(any());
    }

    @Test
    void 검증이_VERIFIED가_아니면_위조_의심으로_거부() {
        Ticket ticket = ticketFor(openConcert());
        verified(VerifyStatus.TAMPERED);
        assertEquals(CheckinService.TAMPER_SUSPECTED, checkinService.scanQr(ticket.getQrToken()).reason());
        verified(VerifyStatus.UNKNOWN);
        assertEquals(CheckinService.TAMPER_SUSPECTED, checkinService.scanQr(ticket.getQrToken()).reason());
        assertEquals(TicketStatus.ACTIVE, ticketRepository.findById(ticket.getTicketId()).orElseThrow().getStatus());
    }

    @Test
    void 입장_성공하면_CHECKED_IN과_버전을_올리고_기록_전송() {
        Ticket ticket = ticketFor(openConcert());
        verified(VerifyStatus.VERIFIED);

        CheckinResultDto result = checkinService.scanQr(ticket.getQrToken());

        assertTrue(result.success());
        Ticket checkedIn = ticketRepository.findById(ticket.getTicketId()).orElseThrow();
        Seat seat = seatRepository.findById(ticket.getSeatId()).orElseThrow();
        assertEquals(TicketStatus.CHECKED_IN, checkedIn.getStatus());
        assertNotNull(checkedIn.getCheckedInAt());
        assertEquals(ticket.getVersion() + 1, checkedIn.getVersion());
        assertEquals(seat.getRecordVersion(), checkedIn.getVersion());
        verify(checkinAccess).recordCheckin(any());
        assertEquals(0, outboxRepository.count());
    }

    @Test
    void 입장_기록_전송이_실패하면_Outbox에_넣고_입장은_허용() {
        Ticket ticket = ticketFor(openConcert());
        verified(VerifyStatus.VERIFIED);
        doThrow(new FairChainException(0, "timeout", null)).when(checkinAccess).recordCheckin(any());

        assertTrue(checkinService.scanQr(ticket.getQrToken()).success());

        RecordOutbox outbox = outboxRepository.findAll().get(0);
        assertEquals(OutboxStatus.PENDING, outbox.getStatus());
        assertEquals(RecordType.CHECKIN, outbox.getPayload().type());
        Ticket checkedIn = ticketRepository.findById(ticket.getTicketId()).orElseThrow();
        assertEquals(recordFactory.build(checkedIn, RecordType.CHECKIN), outbox.getPayload());
    }

    @Test
    void 스태프_등록과_로그인() {
        checkinService.registerStaff(new GateStaffRegisterDto("gate1", "gatepass1", "게이트1"));
        assertThrows(ConflictException.class,
                () -> checkinService.registerStaff(new GateStaffRegisterDto("gate1", "other1234", "게이트")));
        assertEquals("gate1", checkinService.login(new LoginRequestDto("gate1", "gatepass1")).getLoginId());
        assertThrows(UnauthorizedException.class, () -> checkinService.login(new LoginRequestDto("gate1", "wrong")));
        assertFalse(gateStaffRepository.findByLoginId("gate1").orElseThrow().getPassword().equals("gatepass1"));
    }
}
