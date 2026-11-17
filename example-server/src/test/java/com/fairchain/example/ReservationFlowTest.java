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
import com.fairchain.example.reservation.ReallocationRound;
import com.fairchain.example.reservation.ReallocationQueryService;
import com.fairchain.example.reservation.ReallocationRoundRepository;
import com.fairchain.example.reservation.ReallocationService;
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
    @Autowired ReallocationService reallocationService;
    @Autowired ReallocationQueryService reallocationQueryService;
    @Autowired ReallocationRoundRepository roundRepository;
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

    /** 재배정 시험용 — 공연이 열흘 뒤라 취소표가 보류된다 (기본 공연은 1시간 뒤라 "공연 임박") */
    private void useFarConcert() {
        LocalDateTime now = LocalDateTime.now();
        concert = concertRepository.save(new Concert("열흘 뒤 공연", now.minusDays(1), now.plusDays(10), now.plusDays(10).plusHours(3), "테스트홀"));
        SeatGrade grade = seatGradeRepository.save(new SeatGrade(concert.getId(), "R", BigDecimal.valueOf(100000)));
        seat = seatRepository.save(new Seat(concert.getId(), grade.getId(), "A1"));
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
    void cancelAfterSellOutHoldsTheSeatUntilTheOfferTime() {
        useFarConcert();
        Ticket ticket = ticketRepository.findById(reserve(10L).ticketId()).orElseThrow();
        when(reservationAccess.registerRecord(any())).thenReturn(new RecordResponseDto(true));

        reservationService.cancel(ticket.getTicketId(), 10L);

        Seat held = seatRepository.findById(seat.getId()).orElseThrow();
        assertEquals(SeatStatus.CANCELLED, held.getStatus());
        assertTrue(held.getCancelledAt() != null);
        assertTrue(roundRepository.findOpen(concert.getId()).isEmpty(), "제안 시각 전에는 회차를 열지 않는다");
        assertFalse(reservationService.reserve(new ReserveRequestDto(seat.getId(), concert.getId(), null), 30L).success(),
                "보류 중인 취소표는 일반 예매로 살 수 없다");
    }

    @Test
    void offerRoundGoesDownTheListOneAtATimeAndTheFirstCandidateCanBuy() {
        useFarConcert();
        Ticket ticket = ticketRepository.findById(reserve(10L).ticketId()).orElseThrow();
        when(reservationAccess.registerRecord(any())).thenReturn(new RecordResponseDto(true));
        when(reservationAccess.getReallocationCandidates(concert.getId()))
                .thenReturn(new ReallocationListDto(concert.getId(), List.of(120L, 121L)));
        reservationService.cancel(ticket.getTicketId(), 10L);

        reallocationService.offerNow(concert.getId());

        var offers = reallocationQueryService.openOffers(120L);
        assertEquals(1, offers.size());
        assertEquals(1, offers.get(0).seats().size());
        assertTrue(reallocationQueryService.openOffers(121L).isEmpty(), "두 번째 후보는 첫 후보가 응답하지 않을 때까지 기다린다");

        ReserveResultDto bought = reservationService.reserve(
                new ReserveRequestDto(seat.getId(), concert.getId(), offers.get(0).offerToken()), 120L);
        assertTrue(bought.success());
        assertEquals(4L, ticketRepository.findById(bought.ticketId()).orElseThrow().getVersion());
        assertEquals(SeatStatus.RESERVED, seatRepository.findById(seat.getId()).orElseThrow().getStatus());
        assertEquals(ReallocationRound.Status.DONE, roundRepository.findByConcertIdOrderByIdDesc(concert.getId()).get(0).getStatus(), "취소표가 다 나가면 회차 종료");
        assertTrue(reallocationQueryService.openOffers(121L).isEmpty());
    }

    @Test
    void whenTheListRunsOutTheSeatIsScheduledForGeneralSaleAndReleaseNowOpensIt() {
        useFarConcert();
        Ticket ticket = ticketRepository.findById(reserve(10L).ticketId()).orElseThrow();
        when(reservationAccess.registerRecord(any())).thenReturn(new RecordResponseDto(true));
        when(reservationAccess.getReallocationCandidates(concert.getId()))
                .thenReturn(new ReallocationListDto(concert.getId(), List.of()));
        reservationService.cancel(ticket.getTicketId(), 10L);

        reallocationService.offerNow(concert.getId());

        ReallocationRound round = roundRepository.findByConcertIdOrderByIdDesc(concert.getId()).get(0);
        assertEquals(ReallocationRound.Status.RELEASE_PENDING, round.getStatus());
        assertTrue(round.getReleaseAt().isAfter(LocalDateTime.now()), "다음 날 일반 판매 시각까지 기다린다");
        assertEquals(SeatStatus.CANCELLED, seatRepository.findById(seat.getId()).orElseThrow().getStatus());

        assertEquals(1, reallocationService.releaseNow(concert.getId()));
        assertEquals(SeatStatus.EMPTY, seatRepository.findById(seat.getId()).orElseThrow().getStatus());
        assertEquals(ReallocationRound.Status.DONE, roundRepository.findById(round.getId()).orElseThrow().getStatus());
    }

    @Test
    void cancelInTheFinalDayGoesStraightBackToSale() {
        Ticket ticket = ticketRepository.findById(reserve(10L).ticketId()).orElseThrow();
        when(reservationAccess.registerRecord(any())).thenReturn(new RecordResponseDto(true));

        reservationService.cancel(ticket.getTicketId(), 10L);

        assertEquals(SeatStatus.EMPTY, seatRepository.findById(seat.getId()).orElseThrow().getStatus(),
                "공연 하루 전이면 매진 후 취소라도 명단을 돌리지 않고 바로 판매");
    }

    @Test
    void anotherMemberCannotUseSomeoneElsesOffer() {
        useFarConcert();
        Ticket ticket = ticketRepository.findById(reserve(10L).ticketId()).orElseThrow();
        when(reservationAccess.registerRecord(any())).thenReturn(new RecordResponseDto(true));
        when(reservationAccess.getReallocationCandidates(concert.getId()))
                .thenReturn(new ReallocationListDto(concert.getId(), List.of(220L)));
        reservationService.cancel(ticket.getTicketId(), 10L);
        reallocationService.offerNow(concert.getId());
        String token = reallocationQueryService.openOffers(220L).get(0).offerToken();

        assertThrows(ApiException.class,
                () -> reservationService.reserve(new ReserveRequestDto(seat.getId(), concert.getId(), token), 221L));
        assertThrows(ApiException.class,
                () -> reservationService.reserve(new ReserveRequestDto(seat.getId(), concert.getId(), "not-a-token"), 220L));
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
