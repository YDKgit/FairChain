package com.fairchain.example.checkin;

import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.dto.VerifyStatus;
import com.fairchain.example.checkin.CheckinDtos.CheckinResultDto;
import com.fairchain.example.checkin.CheckinDtos.GateStaffRegisterDto;
import com.fairchain.example.checkin.CheckinDtos.StaffLoginDto;
import com.fairchain.example.common.ApiException;
import com.fairchain.example.common.TicketFingerprint;
import com.fairchain.example.concert.Concert;
import com.fairchain.example.concert.ConcertRepository;
import com.fairchain.example.reservation.RecordDispatchService;
import com.fairchain.example.reservation.Seat;
import com.fairchain.example.reservation.SeatRepository;
import com.fairchain.example.reservation.Ticket;
import com.fairchain.example.reservation.TicketRepository;
import com.fairchain.example.reservation.TicketStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/** SS-04 QR 스캔 · 입장 처리 (SD-401-01) */
@Service
public class CheckinService {

    private final GateStaffRepository staffRepository;
    private final TicketRepository ticketRepository;
    private final SeatRepository seatRepository;
    private final ConcertRepository concertRepository;
    private final CheckinAccess checkinAccess;
    private final RecordDispatchService recordDispatchService;
    private final TicketFingerprint fingerprint;
    private final BCryptPasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    public CheckinService(GateStaffRepository staffRepository, TicketRepository ticketRepository,
                          SeatRepository seatRepository, ConcertRepository concertRepository,
                          CheckinAccess checkinAccess, RecordDispatchService recordDispatchService,
                          TicketFingerprint fingerprint, BCryptPasswordEncoder passwordEncoder,
                          PlatformTransactionManager transactionManager) {
        this.staffRepository = staffRepository;
        this.ticketRepository = ticketRepository;
        this.seatRepository = seatRepository;
        this.concertRepository = concertRepository;
        this.checkinAccess = checkinAccess;
        this.recordDispatchService = recordDispatchService;
        this.fingerprint = fingerprint;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public GateStaff login(StaffLoginDto dto) {
        return staffRepository.findByLoginId(dto == null ? null : dto.loginId())
                .filter(s -> passwordEncoder.matches(dto.password(), s.getPassword()))
                .orElseThrow(() -> ApiException.unauthorized("아이디 또는 비밀번호가 틀렸습니다"));
    }

    /** 티켓 조회 → 상태 · 공연 기간 확인 → FairChain 지문 검증 → CHECKED_IN 저장 + 입장 기록 */
    public CheckinResultDto scanQr(String qrToken) {
        Ticket ticket = ticketRepository.findByQrToken(qrToken == null ? "" : qrToken.trim()).orElse(null);
        if (ticket == null) {
            return CheckinResultDto.deny("유효하지 않은 QR");
        }
        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            return CheckinResultDto.deny("취소된 티켓");
        }
        if (ticket.getStatus() == TicketStatus.CHECKED_IN) {
            return CheckinResultDto.deny("이미 입장한 티켓");
        }
        Concert concert = concertRepository.findById(ticket.getConcertId()).orElseThrow();
        if (!concert.isEntryTime(LocalDateTime.now())) {
            return CheckinResultDto.deny("입장 가능 시간이 아님 (공연 시작 3시간 전부터)");
        }
        VerifyResultDto verified = checkinAccess.verify(ticket);
        if (verified.status() != VerifyStatus.VERIFIED) {
            return CheckinResultDto.deny("위조 의심으로 입장 거부"
                    + (verified.reason() == null ? "" : " — " + verified.reason()));
        }
        CheckinResultDto result = transactionTemplate.execute(status -> {
            Seat seat = seatRepository.findByIdForUpdate(ticket.getSeatId()).orElseThrow();
            Ticket current = ticketRepository.findById(ticket.getTicketId()).orElseThrow();
            if (current.getStatus() != TicketStatus.ACTIVE) {     // 같은 QR을 동시에 두 번 찍은 경우
                return CheckinResultDto.deny("이미 입장한 티켓");
            }
            long version = seat.nextVersion();
            current.checkIn(version);
            recordDispatchService.enqueue(seat.getId(), fingerprint.record(RecordType.CHECKIN,
                    current.getConcertId(), seat.getId(), current.getMemberId(), version));
            return new CheckinResultDto(true, null, concert.getTitle(), seat.getSeatNumber());
        });
        if (result != null && result.success()) {
            recordDispatchService.dispatchPending();
        }
        return result;
    }

    @Transactional
    public void registerStaff(GateStaffRegisterDto dto) {
        if (dto == null || blank(dto.loginId()) || blank(dto.password()) || blank(dto.name())) {
            throw ApiException.badRequest("아이디 · 비밀번호 · 이름은 필수입니다");
        }
        if (staffRepository.findByLoginId(dto.loginId()).isPresent()) {
            throw ApiException.conflict("이미 사용 중인 스태프 아이디입니다");
        }
        staffRepository.save(new GateStaff(dto.loginId().trim(), passwordEncoder.encode(dto.password()), dto.name().trim()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
