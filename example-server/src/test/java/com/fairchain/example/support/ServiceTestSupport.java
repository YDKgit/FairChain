package com.fairchain.example.support;

import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.dto.VerifyStatus;
import com.fairchain.example.entity.Concert;
import com.fairchain.example.entity.Member;
import com.fairchain.example.entity.Seat;
import com.fairchain.example.entity.SeatGrade;
import com.fairchain.example.entity.Ticket;
import com.fairchain.example.repository.ConcertRepository;
import com.fairchain.example.repository.GateStaffRepository;
import com.fairchain.example.repository.MemberRepository;
import com.fairchain.example.repository.OperatorRepository;
import com.fairchain.example.repository.OrderRepository;
import com.fairchain.example.repository.ReallocationOfferRepository;
import com.fairchain.example.repository.RecordOutboxRepository;
import com.fairchain.example.repository.SeatGradeRepository;
import com.fairchain.example.repository.SeatRepository;
import com.fairchain.example.repository.TicketRepository;
import com.fairchain.example.requester.AdminAccess;
import com.fairchain.example.requester.CheckinAccess;
import com.fairchain.example.requester.QueueAccess;
import com.fairchain.example.requester.RecordFactory;
import com.fairchain.example.requester.ReservationAccess;
import com.fairchain.example.requester.TicketVerificationAccess;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** 서비스 통합 테스트 공통 — H2 + FairChain Access는 Mockito, 시계는 MutableClock */
@SpringBootTest
@ActiveProfiles({"test", "fairchain-mock"})
@Import(TestClockConfig.class)
public abstract class ServiceTestSupport {

    protected static final String PASSWORD = "password1";

    @MockBean protected QueueAccess queueAccess;
    @MockBean protected ReservationAccess reservationAccess;
    @MockBean protected CheckinAccess checkinAccess;
    @MockBean protected TicketVerificationAccess ticketVerificationAccess;
    @MockBean protected AdminAccess adminAccess;

    @Autowired protected MutableClock clock;
    @Autowired protected RecordFactory recordFactory;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected MemberRepository memberRepository;
    @Autowired protected ConcertRepository concertRepository;
    @Autowired protected SeatGradeRepository seatGradeRepository;
    @Autowired protected SeatRepository seatRepository;
    @Autowired protected TicketRepository ticketRepository;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected ReallocationOfferRepository offerRepository;
    @Autowired protected RecordOutboxRepository outboxRepository;
    @Autowired protected OperatorRepository operatorRepository;
    @Autowired protected GateStaffRepository gateStaffRepository;

    @BeforeEach
    void resetState() {
        clock.reset();
        outboxRepository.deleteAllInBatch();
        offerRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        ticketRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        seatGradeRepository.deleteAllInBatch();
        concertRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
        gateStaffRepository.deleteAllInBatch();
        operatorRepository.deleteAllInBatch();

        // 기록 스냅샷은 실제 규칙(RecordFactory)으로
        when(reservationAccess.buildRecord(any(), any())).thenAnswer(invocation ->
                recordFactory.build(invocation.getArgument(0), invocation.<RecordType>getArgument(1)));
        when(checkinAccess.buildRecord(any())).thenAnswer(invocation ->
                recordFactory.build(invocation.getArgument(0), RecordType.CHECKIN));
    }

    protected LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    protected void admitAll() {
        when(reservationAccess.verifyAdmission(anyLong(), anyLong()))
                .thenReturn(new VerifyResultDto(VerifyStatus.VERIFIED, null));
    }

    /** 예매 오픈 + 공연 기간 중인 공연 */
    protected Concert openConcert() {
        return concertRepository.save(new Concert("테스트 공연", now().minusDays(1), now().minusHours(1),
                now().plusDays(1), "테스트홀"));
    }

    protected Concert futureConcert() {
        return concertRepository.save(new Concert("미래 공연", now().plusDays(7), now().plusDays(14),
                now().plusDays(14).plusHours(3), "테스트홀"));
    }

    protected SeatGrade grade(Concert concert) {
        return seatGradeRepository.save(new SeatGrade(concert.getId(), "VIP", new BigDecimal("150000.00")));
    }

    protected Seat seat(SeatGrade grade, String seatNumber) {
        return seatRepository.save(new Seat(grade.getId(), seatNumber));
    }

    protected Seat cancelledSeat(SeatGrade grade, String seatNumber) {
        Seat seat = new Seat(grade.getId(), seatNumber);
        seat.markCancelled();
        return seatRepository.save(seat);
    }

    protected Member member(String loginId) {
        return memberRepository.save(new Member(loginId, passwordEncoder.encode(PASSWORD), "홍길동",
                "010-1234-5678", now()));
    }

    /** 예매된 좌석의 ACTIVE 티켓 (DB 직접 저장) */
    protected Ticket activeTicket(Member member, Concert concert, Seat seat) {
        seat.reserve(member.getId(), now());
        seatRepository.save(seat);
        return ticketRepository.save(new Ticket(member.getId(), seat.getId(), concert.getId(),
                UUID.randomUUID().toString(), seat.getRecordVersion(), now()));
    }

    protected RecordRequestDto anyRecord() {
        return any(RecordRequestDto.class);
    }
}
