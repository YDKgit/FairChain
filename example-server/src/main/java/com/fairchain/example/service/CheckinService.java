package com.fairchain.example.service;

import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.dto.VerifyStatus;
import com.fairchain.example.common.ConflictException;
import com.fairchain.example.common.UnauthorizedException;
import com.fairchain.example.config.ExampleProperties;
import com.fairchain.example.dto.CheckinResultDto;
import com.fairchain.example.dto.GateStaffRegisterDto;
import com.fairchain.example.dto.LoginRequestDto;
import com.fairchain.example.entity.Concert;
import com.fairchain.example.entity.GateStaff;
import com.fairchain.example.entity.RecordOutbox;
import com.fairchain.example.entity.Seat;
import com.fairchain.example.entity.Ticket;
import com.fairchain.example.entity.TicketStatus;
import com.fairchain.example.repository.ConcertRepository;
import com.fairchain.example.repository.GateStaffRepository;
import com.fairchain.example.repository.RecordOutboxRepository;
import com.fairchain.example.repository.SeatRepository;
import com.fairchain.example.repository.TicketRepository;
import com.fairchain.example.requester.CheckinAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/** C-0404 체크인 서비스 */
@Service
public class CheckinService {

    public static final String INVALID_QR = "유효하지 않은 QR";
    public static final String CANCELLED_TICKET = "취소된 티켓";
    public static final String ALREADY_CHECKED_IN = "이미 입장한 티켓";
    public static final String OUT_OF_PERIOD = "입장 가능 시간이 아님";
    public static final String TAMPER_SUSPECTED = "위조 의심으로 입장 거부";

    private static final Logger log = LoggerFactory.getLogger(CheckinService.class);

    private final CheckinAccess checkinAccess;
    private final GateStaffRepository gateStaffRepository;
    private final TicketRepository ticketRepository;
    private final SeatRepository seatRepository;
    private final ConcertRepository concertRepository;
    private final RecordOutboxRepository outboxRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final ExampleProperties properties;
    private final Clock clock;

    public CheckinService(CheckinAccess checkinAccess, GateStaffRepository gateStaffRepository,
                          TicketRepository ticketRepository, SeatRepository seatRepository,
                          ConcertRepository concertRepository, RecordOutboxRepository outboxRepository,
                          PasswordEncoder passwordEncoder, TransactionTemplate transactionTemplate,
                          ExampleProperties properties, Clock clock) {
        this.checkinAccess = checkinAccess;
        this.gateStaffRepository = gateStaffRepository;
        this.ticketRepository = ticketRepository;
        this.seatRepository = seatRepository;
        this.concertRepository = concertRepository;
        this.outboxRepository = outboxRepository;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GateStaff login(LoginRequestDto dto) {
        return gateStaffRepository.findByLoginId(dto.loginId())
                .filter(staff -> passwordEncoder.matches(dto.password(), staff.getPassword()))
                .orElseThrow(() -> new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다"));
    }

    @Transactional
    public GateStaff registerStaff(GateStaffRegisterDto dto) {
        if (gateStaffRepository.findByLoginId(dto.loginId()).isPresent()) {
            throw new ConflictException("이미 사용 중인 아이디입니다");
        }
        return gateStaffRepository.save(new GateStaff(dto.loginId(), passwordEncoder.encode(dto.password()), dto.name()));
    }

    /** SD-401-01 — QR 값은 가공하지 않고 그대로 조회한다 */
    public CheckinResultDto scanQr(String qrToken) {
        Optional<Ticket> found = ticketRepository.findByQrToken(qrToken);
        if (found.isEmpty()) {
            return CheckinResultDto.fail(INVALID_QR);
        }
        Ticket ticket = found.get();
        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            return CheckinResultDto.fail(CANCELLED_TICKET);
        }
        if (ticket.getStatus() == TicketStatus.CHECKED_IN) {
            return CheckinResultDto.fail(ALREADY_CHECKED_IN);
        }
        if (!properties.checkin().ignoreEventPeriod() && !isInEventPeriod(ticket.getConcertId())) {
            return CheckinResultDto.fail(OUT_OF_PERIOD);
        }
        VerifyResultDto verified = checkinAccess.verify(ticket);
        if (verified == null || verified.status() != VerifyStatus.VERIFIED) {
            return CheckinResultDto.fail(TAMPER_SUSPECTED);
        }

        Ticket checkedIn = transactionTemplate.execute(status -> {
            Seat seat = seatRepository.findByIdForUpdate(ticket.getSeatId()).orElseThrow();
            Ticket locked = ticketRepository.findById(ticket.getTicketId()).orElseThrow();
            if (locked.getStatus() != TicketStatus.ACTIVE) {
                return null; // 동시에 다른 게이트에서 처리됨
            }
            locked.checkIn(LocalDateTime.now(clock), seat.nextRecordVersion());
            seatRepository.save(seat);
            return ticketRepository.save(locked);
        });
        if (checkedIn == null) {
            return CheckinResultDto.fail(ALREADY_CHECKED_IN);
        }

        // 보완: 기록 전송이 실패하면 Outbox로 재전송에 맡기고 입장은 허용한다
        try {
            checkinAccess.recordCheckin(checkedIn);
        } catch (RuntimeException e) {
            RecordRequestDto record = checkinAccess.buildRecord(checkedIn);
            outboxRepository.save(new RecordOutbox(record, LocalDateTime.now(clock)));
            log.warn("입장 기록 전송 실패 — Outbox 재전송 대기 ticketId={}: {}", checkedIn.getTicketId(), e.getMessage());
        }
        return new CheckinResultDto(true, null);
    }

    private boolean isInEventPeriod(Long concertId) {
        return concertRepository.findById(concertId)
                .map((Concert concert) -> concert.isInEventPeriod(LocalDateTime.now(clock)))
                .orElse(false);
    }
}
