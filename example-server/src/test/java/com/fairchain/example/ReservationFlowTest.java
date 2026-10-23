package com.fairchain.example;

import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.dto.VerifyStatus;
import com.fairchain.example.checkin.CheckinAccess;
import com.fairchain.example.checkin.CheckinService;
import com.fairchain.example.common.TicketFingerprint;
import com.fairchain.example.concert.Concert;
import com.fairchain.example.concert.ConcertRepository;
import com.fairchain.example.concert.SeatGrade;
import com.fairchain.example.concert.SeatGradeRepository;
import com.fairchain.example.operator.AdminAccess;
import com.fairchain.example.queue.QueueAccess;
import com.fairchain.example.common.ApiException;
import com.fairchain.example.reservation.OutboxStatus;
import com.fairchain.example.reservation.RecordDispatchService;
import com.fairchain.example.reservation.RecordOutboxRepository;
import com.fairchain.example.reservation.ReservationAccess;
import com.fairchain.example.reservation.ReservationDtos.ReserveRequestDto;
import com.fairchain.example.reservation.ReservationDtos.ReserveResultDto;
import com.fairchain.example.reservation.ReservationService;
import com.fairchain.example.reservation.Seat;
import com.fairchain.example.reservation.SeatRepository;
import com.fairchain.example.reservation.SeatStatus;
import com.fairchain.example.reservation.Ticket;
import com.fairchain.example.reservation.TicketRepository;
import com.fairchain.example.reservation.TicketStatus;
import com.fairchain.example.ticket.TicketVerificationAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 예제서버 예매 흐름 — 실제 DB(H2)로, FairChain 호출은 가짜로 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:example-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "example.seed.concerts=false",
        "example.fairchain.url=http://localhost:1",
        "example.fairchain.api-key=test",
        "example.scheduling.enabled=false",
        "spring.main.banner-mode=off"
})
class ReservationFlowTest {

    @MockBean ReservationAccess reservationAccess;
    @MockBean QueueAccess queueAccess;
    @MockBean TicketVerificationAccess ticketVerificationAccess;
    @MockBean CheckinAccess checkinAccess;
    @MockBean AdminAccess adminAccess;

    @Autowired ReservationService reservationService;
    @Autowired CheckinService checkinService;
    @Autowired ConcertRepository concertRepository;
    @Autowired SeatGradeRepository seatGradeRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired TicketRepository ticketRepository;
    @Autowired RecordOutboxRepository outboxRepository;
    @Autowired TicketFingerprint fingerprint;
    @Autowired RecordDispatchService recordDispatchService;

    private Concert concert;
    private Seat seat;

    @BeforeEach
    void setUp() {
        reset(reservationAccess, checkinAccess);
        LocalDateTime now = LocalDateTime.now();
        concert = concertRepository.save(new Concert("테스트 공연", now.minusDays(1), now.plusHours(1), now.plusHours(4), "테스트홀"));
        SeatGrade grade = seatGradeRepository.save(new SeatGrade(concert.getId(), "R", BigDecimal.valueOf(100000)));
        seat = seatRepository.save(new Seat(concert.getId(), grade.getId(), "A1"));
        when(reservationAccess.verifyAdmission(anyLong(), anyLong())).thenReturn(new VerifyResultDto(VerifyStatus.VERIFIED, null));
        when(reservationAccess.registerRecord(any())).thenReturn(new RecordResponseDto(false));
    }

    private ReserveResultDto reserve(long memberId) {
        return reservationService.reserve(new ReserveRequestDto(seat.getId(), concert.getId(), null), memberId);
    }

    @Test
    void purchaseSendsTheTicketFingerprintAsVersion2() {
        ReserveResultDto result = reserve(10L);

        assertTrue(result.success());
        Ticket ticket = ticketRepository.findById(result.ticketId()).orElseThrow();
        assertEquals(TicketStatus.ACTIVE, ticket.getStatus());
        assertEquals(2L, ticket.getVersion());
        ArgumentCaptor<RecordRequestDto> sent = ArgumentCaptor.forClass(RecordRequestDto.class);
        verify(reservationAccess).registerRecord(sent.capture());
        RecordRequestDto record = sent.getValue();
        assertEquals(RecordType.PURCHASE, record.type());
        assertEquals(fingerprint.recordKey(seat.getId()), record.recordKey());
        assertEquals(fingerprint.recordHash(concert.getId(), seat.getId(), 10L, "ACTIVE", 2L), record.recordHash());
        assertEquals(SeatStatus.RESERVED, seatRepository.findById(seat.getId()).orElseThrow().getStatus());
    }

    @Test
    void withoutAdmissionTheSeatCannotBeBought() {
        when(reservationAccess.verifyAdmission(anyLong(), anyLong())).thenReturn(new VerifyResultDto(VerifyStatus.UNKNOWN, "만료"));

        ReserveResultDto result = reserve(10L);

        assertFalse(result.success());
        assertEquals(SeatStatus.EMPTY, seatRepository.findById(seat.getId()).orElseThrow().getStatus());
    }

    @Test
    void twoUsersBuyingTheSameSeatAtOnceOnlyOneSucceeds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<ReserveResultDto>> buyers = List.of(() -> reserve(10L), () -> reserve(11L));
        List<ReserveResultDto> results = pool.invokeAll(buyers).stream().map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        pool.shutdown();

        assertEquals(1, results.stream().filter(ReserveResultDto::success).count());
        assertEquals(1, ticketRepository.findAll().stream().filter(t -> t.getSeatId().equals(seat.getId())).count());
        assertEquals(2L, seatRepository.findById(seat.getId()).orElseThrow().getRecordVersion());
    }

    @Test
    void oneTicketPerMemberPerConcert() {
        Seat second = seatRepository.save(new Seat(concert.getId(), seat.getSeatGradeId(), "A2"));
        assertTrue(reserve(10L).success());

        ReserveResultDto again = reservationService.reserve(new ReserveRequestDto(second.getId(), concert.getId(), null), 10L);

        assertFalse(again.success());
    }

    @Test
    void cancelBeforeSellOutReturnsTheSeatToSale() {
        Ticket ticket = ticketRepository.findById(reserve(10L).ticketId()).orElseThrow();

        reservationService.cancel(ticket.getTicketId(), 10L);

        assertEquals(TicketStatus.CANCELLED, ticketRepository.findById(ticket.getTicketId()).orElseThrow().getStatus());
        assertEquals(SeatStatus.EMPTY, seatRepository.findById(seat.getId()).orElseThrow().getStatus());
        assertEquals(3L, seatRepository.findById(seat.getId()).orElseThrow().getRecordVersion());
    }

    @Test
    void cancelAfterSellOutOffersTheSeatToTheFirstCandidateWhoCanThenBuyIt() {
        Ticket ticket = ticketRepository.findById(reserve(10L).ticketId()).orElseThrow();
        when(reservationAccess.registerRecord(any())).thenReturn(new RecordResponseDto(true));
        when(reservationAccess.getReallocationCandidates(concert.getId()))
                .thenReturn(new ReallocationListDto(concert.getId(), List.of(20L, 21L)));

        reservationService.cancel(ticket.getTicketId(), 10L);

        assertEquals(SeatStatus.CANCELLED, seatRepository.findById(seat.getId()).orElseThrow().getStatus());
        var offers = reservationService.openOffers(20L);
        assertEquals(1, offers.size());
        assertTrue(reservationService.openOffers(21L).isEmpty(), "두 번째 후보는 첫 후보가 응답하지 않을 때까지 기다린다");

        ReserveResultDto bought = reservationService.reserve(
                new ReserveRequestDto(seat.getId(), concert.getId(), offers.get(0).offerToken()), 20L);
        assertTrue(bought.success());
        assertEquals(4L, ticketRepository.findById(bought.ticketId()).orElseThrow().getVersion());
        assertEquals(SeatStatus.RESERVED, seatRepository.findById(seat.getId()).orElseThrow().getStatus());
    }

    @Test
    void anotherMemberCannotUseSomeoneElsesOffer() {
        Ticket ticket = ticketRepository.findById(reserve(10L).ticketId()).orElseThrow();
        when(reservationAccess.registerRecord(any())).thenReturn(new RecordResponseDto(true));
        when(reservationAccess.getReallocationCandidates(concert.getId()))
                .thenReturn(new ReallocationListDto(concert.getId(), List.of(20L)));
        reservationService.cancel(ticket.getTicketId(), 10L);
        String token = reservationService.openOffers(20L).get(0).offerToken();

        assertThrows(ApiException.class,
                () -> reservationService.reserve(new ReserveRequestDto(seat.getId(), concert.getId(), token), 21L));
        assertThrows(ApiException.class,
                () -> reservationService.reserve(new ReserveRequestDto(seat.getId(), concert.getId(), "not-a-token"), 20L));
    }

    @Test
    void checkInOnceThenTheSameQrIsRefused() {
        Ticket ticket = ticketRepository.findById(reserve(10L).ticketId()).orElseThrow();
        when(checkinAccess.verify(any())).thenReturn(new VerifyResultDto(VerifyStatus.VERIFIED, null));

        var first = checkinService.scanQr(ticket.getQrToken());
        var second = checkinService.scanQr(ticket.getQrToken());

        assertTrue(first.success());
        assertFalse(second.success());
        assertEquals("이미 입장한 티켓", second.reason());
        Ticket checked = ticketRepository.findById(ticket.getTicketId()).orElseThrow();
        assertEquals(TicketStatus.CHECKED_IN, checked.getStatus());
        assertEquals(3L, checked.getVersion());
    }

    @Test
    void forgedTicketIsNotLetIn() {
        Ticket ticket = ticketRepository.findById(reserve(10L).ticketId()).orElseThrow();
        when(checkinAccess.verify(any())).thenReturn(new VerifyResultDto(VerifyStatus.TAMPERED, "티켓 내용이 블록체인 기록과 다릅니다"));

        var result = checkinService.scanQr(ticket.getQrToken());

        assertFalse(result.success());
        assertEquals(TicketStatus.ACTIVE, ticketRepository.findById(ticket.getTicketId()).orElseThrow().getStatus());
    }

    @Test
    void recordsWaitInTheOutboxWhileFairChainIsDownAndAreSentLater() {
        when(reservationAccess.registerRecord(any())).thenThrow(new ResourceAccessException("connection refused"));

        assertTrue(reserve(10L).success(), "예매는 예제서버 DB에서 끝나고, 기록은 나중에 보낸다");
        assertEquals(1, outboxRepository.findPending().size());

        reset(reservationAccess);
        when(reservationAccess.registerRecord(any())).thenReturn(new RecordResponseDto(false));
        recordDispatchService.dispatchPending();

        assertTrue(outboxRepository.findPending().isEmpty());
        assertEquals(OutboxStatus.SENT, outboxRepository.findAll().get(outboxRepository.findAll().size() - 1).getStatus());
    }
}
