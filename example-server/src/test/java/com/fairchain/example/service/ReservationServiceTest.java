package com.fairchain.example.service;

import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.dto.VerifyStatus;
import com.fairchain.example.common.BadRequestException;
import com.fairchain.example.common.FairChainException;
import com.fairchain.example.common.ForbiddenException;
import com.fairchain.example.common.NotFoundException;
import com.fairchain.example.dto.ReserveRequestDto;
import com.fairchain.example.dto.ReserveResultDto;
import com.fairchain.example.entity.Concert;
import com.fairchain.example.entity.Member;
import com.fairchain.example.entity.OfferStatus;
import com.fairchain.example.entity.Order;
import com.fairchain.example.entity.OutboxStatus;
import com.fairchain.example.entity.RecordOutbox;
import com.fairchain.example.entity.Seat;
import com.fairchain.example.entity.SeatGrade;
import com.fairchain.example.entity.SeatStatus;
import com.fairchain.example.entity.Ticket;
import com.fairchain.example.entity.TicketStatus;
import com.fairchain.example.support.ServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

class ReservationServiceTest extends ServiceTestSupport {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private RecordDispatchService recordDispatchService;

    private Concert concert;
    private SeatGrade grade;
    private Seat seat;

    @BeforeEach
    void setUp() {
        concert = openConcert();
        grade = grade(concert);
        seat = seat(grade, "A-1");
    }

    private ReserveResultDto reserve(Member member, Seat target) {
        return reservationService.reserve(new ReserveRequestDto(target.getId(), concert.getId(), null), member.getId());
    }

    private Seat reload(Seat target) {
        return seatRepository.findById(target.getId()).orElseThrow();
    }

    // ── 일반 예매 ──

    @Test
    void 입장_권한이_없으면_실패() {
        when(reservationAccess.verifyAdmission(anyLong(), anyLong()))
                .thenReturn(new VerifyResultDto(VerifyStatus.UNKNOWN, null));
        ReserveResultDto result = reserve(member("user1"), seat);
        assertFalse(result.success());
        assertEquals(ReservationService.NO_ADMISSION, result.reason());
        assertEquals(0, ticketRepository.count());
    }

    @Test
    void 이미_이_공연_티켓이_있으면_실패() {
        admitAll();
        Member member = member("user1");
        assertTrue(reserve(member, seat).success());
        ReserveResultDto second = reserve(member, seat(grade, "A-2"));
        assertFalse(second.success());
        assertEquals(ReservationService.ALREADY_OWNED, second.reason());
    }

    @Test
    void 이미_예약된_좌석이면_실패() {
        admitAll();
        assertTrue(reserve(member("user1"), seat).success());
        ReserveResultDto result = reserve(member("user2"), seat);
        assertFalse(result.success());
        assertEquals(ReservationService.ALREADY_RESERVED, result.reason());
    }

    @Test
    void 재배정_중인_좌석은_일반_예매_불가() {
        admitAll();
        Seat cancelled = cancelledSeat(grade, "A-9");
        ReserveResultDto result = reserve(member("user1"), cancelled);
        assertFalse(result.success());
        assertEquals(ReservationService.ALREADY_RESERVED, result.reason());
    }

    @Test
    void 다른_공연_좌석이면_실패() {
        admitAll();
        Concert other = openConcert();
        Seat otherSeat = seat(grade(other), "B-1");
        ReserveResultDto result = reserve(member("user1"), otherSeat);
        assertFalse(result.success());
        assertEquals(ReservationService.SEAT_NOT_FOUND, result.reason());
    }

    @Test
    void 예매_성공하면_좌석_티켓_주문_Outbox를_저장하고_버전을_올림() {
        admitAll();
        Member member = member("user1");

        ReserveResultDto result = reserve(member, seat);

        assertTrue(result.success());
        Seat reserved = reload(seat);
        assertEquals(SeatStatus.RESERVED, reserved.getStatus());
        assertEquals(member.getId(), reserved.getReservedBy());
        assertEquals(2L, reserved.getRecordVersion());

        Ticket ticket = ticketRepository.findById(result.ticketId()).orElseThrow();
        assertEquals(TicketStatus.ACTIVE, ticket.getStatus());
        assertEquals(2L, ticket.getVersion());

        Order order = orderRepository.findAll().get(0);
        assertEquals(ticket.getTicketId(), order.getTicketId());
        assertEquals(0, grade.getPrice().compareTo(order.getPaidAmount()));

        RecordOutbox outbox = outboxRepository.findAll().get(0);
        assertEquals(OutboxStatus.PENDING, outbox.getStatus());
        assertEquals(recordFactory.build(ticket, RecordType.PURCHASE), outbox.getPayload());
    }

    @Test
    void 같은_좌석에_동시에_10명이_예매하면_1명만_성공() throws Exception {
        admitAll();
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            members.add(member("racer" + i));
        }
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReserveResultDto>> futures = new ArrayList<>();
        for (Member member : members) {
            futures.add(executor.submit(() -> {
                start.await();
                return reserve(member, seat);
            }));
        }
        start.countDown();
        int success = 0;
        for (Future<ReserveResultDto> future : futures) {
            if (future.get(30, TimeUnit.SECONDS).success()) {
                success++;
            }
        }
        executor.shutdown();

        assertEquals(1, success);
        assertEquals(1, ticketRepository.count());
        assertEquals(2L, reload(seat).getRecordVersion());
    }

    // ── 취소 ──

    private Ticket reservedTicket(Member member) {
        admitAll();
        return ticketRepository.findById(reserve(member, seat).ticketId()).orElseThrow();
    }

    private RecordOutbox cancelOutbox() {
        return outboxRepository.findAll().stream()
                .filter(outbox -> outbox.getPayload().type() == RecordType.CANCEL)
                .findFirst().orElseThrow();
    }

    @Test
    void 매진_전_취소면_좌석을_개방() {
        Member member = member("user1");
        Ticket ticket = reservedTicket(member);
        when(reservationAccess.registerRecord(anyRecord())).thenReturn(new RecordResponseDto(false));

        assertTrue(reservationService.cancel(ticket.getTicketId(), member.getId()));

        Ticket cancelled = ticketRepository.findById(ticket.getTicketId()).orElseThrow();
        assertEquals(TicketStatus.CANCELLED, cancelled.getStatus());
        assertEquals(3L, cancelled.getVersion());
        Seat opened = reload(seat);
        assertEquals(SeatStatus.EMPTY, opened.getStatus());
        assertNull(opened.getReservedBy());
        assertEquals(3L, opened.getRecordVersion());
        assertEquals(OutboxStatus.SENT, cancelOutbox().getStatus());
        assertEquals(recordFactory.build(cancelled, RecordType.CANCEL), cancelOutbox().getPayload());
    }

    @Test
    void 매진_후_취소면_재배정을_시작() {
        Member member = member("user1");
        Member waiting = member("user2");
        Ticket ticket = reservedTicket(member);
        when(reservationAccess.registerRecord(anyRecord())).thenReturn(new RecordResponseDto(true));
        when(reservationAccess.getReallocationCandidates(concert.getId()))
                .thenReturn(new ReallocationListDto(concert.getId(), List.of(waiting.getId())));

        reservationService.cancel(ticket.getTicketId(), member.getId());

        assertEquals(SeatStatus.CANCELLED, reload(seat).getStatus());
        var offer = offerRepository.findAll().get(0);
        assertEquals(waiting.getId(), offer.getMemberId());
        assertEquals(OfferStatus.OFFERED, offer.getStatus());
    }

    @Test
    void 취소_전송이_실패해도_취소는_성공하고_디스패처가_이어서_처리() {
        Member member = member("user1");
        Ticket ticket = reservedTicket(member);
        when(reservationAccess.registerRecord(anyRecord())).thenThrow(new FairChainException(503, "down", null));

        assertTrue(reservationService.cancel(ticket.getTicketId(), member.getId()));
        assertEquals(SeatStatus.CANCELLED, reload(seat).getStatus());
        assertEquals(OutboxStatus.PENDING, cancelOutbox().getStatus());

        // 생성 직후에는 재전송하지 않는다
        recordDispatchService.dispatchPending();
        assertEquals(OutboxStatus.PENDING, cancelOutbox().getStatus());
        assertEquals(0, cancelOutbox().getRetryCount());

        // FairChain 복구 + pending-delay 경과 → 재전송 후 soldOut 분기
        when(reservationAccess.registerRecord(anyRecord())).thenReturn(new RecordResponseDto(false));
        clock.advance(Duration.ofSeconds(11));
        recordDispatchService.dispatchPending();

        assertEquals(OutboxStatus.SENT, cancelOutbox().getStatus());
        assertEquals(SeatStatus.EMPTY, reload(seat).getStatus());
        assertTrue(outboxRepository.findAll().stream().allMatch(outbox -> outbox.getStatus() == OutboxStatus.SENT));
    }

    @Test
    void 재전송이_계속_실패하면_재시도_횟수만_늘어남() {
        Member member = member("user1");
        reservedTicket(member);
        when(reservationAccess.registerRecord(anyRecord())).thenThrow(new FairChainException(0, "timeout", null));
        clock.advance(Duration.ofSeconds(11));

        recordDispatchService.dispatchPending();
        recordDispatchService.dispatchPending();

        RecordOutbox outbox = outboxRepository.findAll().get(0);
        assertEquals(OutboxStatus.PENDING, outbox.getStatus());
        assertEquals(2, outbox.getRetryCount());
    }

    @Test
    void 취소_없는_티켓_404_남의_티켓_403_이미_취소면_400() {
        Member owner = member("user1");
        Ticket ticket = reservedTicket(owner);
        when(reservationAccess.registerRecord(anyRecord())).thenReturn(new RecordResponseDto(false));

        assertThrows(NotFoundException.class, () -> reservationService.cancel(999_999L, owner.getId()));
        assertThrows(ForbiddenException.class, () -> reservationService.cancel(ticket.getTicketId(), member("user2").getId()));
        reservationService.cancel(ticket.getTicketId(), owner.getId());
        assertThrows(BadRequestException.class, () -> reservationService.cancel(ticket.getTicketId(), owner.getId()));
    }

    @Test
    void 좌석_목록은_등급과_상태를_함께() {
        var seats = reservationService.getSeats(concert.getId()).seats();
        assertEquals(1, seats.size());
        assertEquals("VIP", seats.get(0).gradeName());
        assertEquals(SeatStatus.EMPTY, seats.get(0).status());
        assertThrows(NotFoundException.class, () -> reservationService.getSeats(999_999L));
    }
}
