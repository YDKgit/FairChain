package com.fairchain.example.service;

import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.example.dto.ReserveRequestDto;
import com.fairchain.example.dto.ReserveResultDto;
import com.fairchain.example.entity.Concert;
import com.fairchain.example.entity.Member;
import com.fairchain.example.entity.OfferStatus;
import com.fairchain.example.entity.ReallocationOffer;
import com.fairchain.example.entity.Seat;
import com.fairchain.example.entity.SeatGrade;
import com.fairchain.example.entity.SeatStatus;
import com.fairchain.example.entity.Ticket;
import com.fairchain.example.support.ServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReallocationServiceTest extends ServiceTestSupport {

    @Autowired
    private ReallocationService reallocationService;

    @Autowired
    private ReservationService reservationService;

    private Concert concert;
    private SeatGrade grade;
    private Seat seat;

    @BeforeEach
    void setUp() {
        concert = openConcert();
        grade = grade(concert);
        seat = cancelledSeat(grade, "A-1");
    }

    private void candidates(Member... members) {
        when(reservationAccess.getReallocationCandidates(concert.getId())).thenReturn(new ReallocationListDto(
                concert.getId(), java.util.Arrays.stream(members).map(Member::getId).toList()));
    }

    private ReallocationOffer offerOf(Member member) {
        return offerRepository.findAll().stream()
                .filter(offer -> offer.getMemberId().equals(member.getId()) && offer.getSeatId().equals(seat.getId()))
                .findFirst().orElseThrow();
    }

    private Seat reload() {
        return seatRepository.findById(seat.getId()).orElseThrow();
    }

    @Test
    void 명단에서_티켓_보유자와_이미_제안받은_회원은_제외() {
        Member holder = member("holder");
        Member offeredBefore = member("before");
        Member first = member("first");
        Member second = member("second");
        activeTicket(holder, concert, seat(grade, "Z-1"));
        offerRepository.save(new ReallocationOffer(concert.getId(), 12345L, offeredBefore.getId(), 1L));
        candidates(holder, offeredBefore, first, second);

        reallocationService.startReallocation(concert.getId(), seat.getId());

        List<ReallocationOffer> offers = offerRepository.findAll().stream()
                .filter(offer -> offer.getSeatId().equals(seat.getId())).toList();
        assertEquals(2, offers.size());
        assertEquals(OfferStatus.OFFERED, offerOf(first).getStatus());
        assertEquals(3L, offerOf(first).getSequenceNumber());
        assertEquals(OfferStatus.WAITING, offerOf(second).getStatus());
        assertEquals(4L, offerOf(second).getSequenceNumber());
    }

    @Test
    void 기한이_지나면_다음_사람에게_제안하고_마지막도_만료되면_좌석_개방() {
        Member first = member("first");
        Member second = member("second");
        candidates(first, second);
        reallocationService.startReallocation(concert.getId(), seat.getId());

        clock.advance(Duration.ofMinutes(3).plusSeconds(1));
        reallocationService.expireOffers();
        assertEquals(OfferStatus.EXPIRED, offerOf(first).getStatus());
        assertEquals(OfferStatus.OFFERED, offerOf(second).getStatus());
        assertEquals(SeatStatus.CANCELLED, reload().getStatus());

        clock.advance(Duration.ofMinutes(3).plusSeconds(1));
        reallocationService.expireOffers();
        assertEquals(OfferStatus.EXPIRED, offerOf(second).getStatus());
        assertEquals(SeatStatus.EMPTY, reload().getStatus());
    }

    @Test
    void 후보가_없으면_좌석을_일반_예매로_개방() {
        candidates();
        reallocationService.startReallocation(concert.getId(), seat.getId());
        assertEquals(SeatStatus.EMPTY, reload().getStatus());
    }

    @Test
    void 제안_검증_실패_조건() {
        Member first = member("first");
        Member other = member("other");
        candidates(first);
        reallocationService.startReallocation(concert.getId(), seat.getId());
        String token = offerOf(first).getOfferToken();

        assertTrue(reallocationService.verifyOffer(concert.getId(), seat.getId(), first.getId(), token));
        assertFalse(reallocationService.verifyOffer(concert.getId(), seat.getId(), other.getId(), token));
        assertFalse(reallocationService.verifyOffer(concert.getId(), seat.getId() + 1, first.getId(), token));
        assertFalse(reallocationService.verifyOffer(concert.getId() + 1, seat.getId(), first.getId(), token));
        assertFalse(reallocationService.verifyOffer(concert.getId(), seat.getId(), first.getId(), "wrong-token"));

        clock.advance(Duration.ofMinutes(3).plusSeconds(1));
        assertFalse(reallocationService.verifyOffer(concert.getId(), seat.getId(), first.getId(), token));
    }

    @Test
    void 재배정_예매_성공하면_좌석_예약_제안_수락_PURCHASE_기록() {
        Member first = member("first");
        candidates(first);
        reallocationService.startReallocation(concert.getId(), seat.getId());
        String token = offerOf(first).getOfferToken();

        ReserveResultDto result = reservationService.reserve(
                new ReserveRequestDto(seat.getId(), concert.getId(), token), first.getId());

        assertTrue(result.success(), result.reason());
        Seat reserved = reload();
        assertEquals(SeatStatus.RESERVED, reserved.getStatus());
        assertEquals(first.getId(), reserved.getReservedBy());
        assertEquals(2L, reserved.getRecordVersion());
        Ticket ticket = ticketRepository.findById(result.ticketId()).orElseThrow();
        assertEquals(reserved.getRecordVersion(), ticket.getVersion());
        assertEquals(OfferStatus.ACCEPTED, offerOf(first).getStatus());
        assertEquals(RecordType.PURCHASE, outboxRepository.findAll().get(0).getPayload().type());
        verify(reservationAccess, never()).verifyAdmission(anyLong(), anyLong());
    }

    @Test
    void 만료된_제안으로는_재배정_예매_불가() {
        Member first = member("first");
        candidates(first);
        reallocationService.startReallocation(concert.getId(), seat.getId());
        String token = offerOf(first).getOfferToken();
        clock.advance(Duration.ofMinutes(4));

        ReserveResultDto result = reservationService.reserve(
                new ReserveRequestDto(seat.getId(), concert.getId(), token), first.getId());

        assertFalse(result.success());
        assertEquals(ReservationService.INVALID_OFFER, result.reason());
    }

    @Test
    void 내_제안_조회() {
        Member first = member("first");
        Member second = member("second");
        candidates(first, second);
        reallocationService.startReallocation(concert.getId(), seat.getId());

        var offer = reallocationService.getMyOffer(first.getId(), concert.getId()).orElseThrow();
        assertEquals(seat.getId(), offer.seatId());
        assertEquals("A-1", offer.seatNumber());
        assertTrue(reallocationService.getMyOffer(second.getId(), concert.getId()).isEmpty());
    }
}
