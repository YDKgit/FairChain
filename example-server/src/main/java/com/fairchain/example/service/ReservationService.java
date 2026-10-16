package com.fairchain.example.service;

import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.dto.VerifyStatus;
import com.fairchain.example.common.BadRequestException;
import com.fairchain.example.common.ForbiddenException;
import com.fairchain.example.common.NotFoundException;
import com.fairchain.example.dto.ReserveRequestDto;
import com.fairchain.example.dto.ReserveResultDto;
import com.fairchain.example.dto.SeatDto;
import com.fairchain.example.dto.SeatListResponseDto;
import com.fairchain.example.entity.Order;
import com.fairchain.example.entity.ReallocationOffer;
import com.fairchain.example.entity.RecordOutbox;
import com.fairchain.example.entity.Seat;
import com.fairchain.example.entity.SeatGrade;
import com.fairchain.example.entity.SeatStatus;
import com.fairchain.example.entity.Ticket;
import com.fairchain.example.entity.TicketStatus;
import com.fairchain.example.repository.ConcertRepository;
import com.fairchain.example.repository.OrderRepository;
import com.fairchain.example.repository.RecordOutboxRepository;
import com.fairchain.example.repository.SeatGradeRepository;
import com.fairchain.example.repository.SeatRepository;
import com.fairchain.example.repository.TicketRepository;
import com.fairchain.example.requester.ReservationAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * C-0306 예매 서비스.
 * DB 변경과 기록(Outbox)은 한 트랜잭션에 저장하고, FairChain 전송은 커밋 후에 한다(트랜잭션 경계가 메서드 중간이라 TransactionTemplate 사용).
 */
@Service
public class ReservationService {

    public static final String NO_ADMISSION = "입장 권한 없음";
    public static final String ALREADY_OWNED = "이미 이 공연 티켓 보유";
    public static final String SEAT_NOT_FOUND = "좌석 없음";
    public static final String ALREADY_RESERVED = "이미 예약된 좌석";
    public static final String INVALID_OFFER = "제안이 유효하지 않음";
    public static final String NOT_REALLOCATING = "재배정 중인 좌석이 아님";

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationAccess reservationAccess;
    private final ReallocationService reallocationService;
    private final ConcertRepository concertRepository;
    private final SeatGradeRepository seatGradeRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final OrderRepository orderRepository;
    private final RecordOutboxRepository outboxRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ReservationService(ReservationAccess reservationAccess, ReallocationService reallocationService,
                              ConcertRepository concertRepository, SeatGradeRepository seatGradeRepository,
                              SeatRepository seatRepository, TicketRepository ticketRepository,
                              OrderRepository orderRepository, RecordOutboxRepository outboxRepository,
                              TransactionTemplate transactionTemplate, Clock clock) {
        this.reservationAccess = reservationAccess;
        this.reallocationService = reallocationService;
        this.concertRepository = concertRepository;
        this.seatGradeRepository = seatGradeRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** SD-301-01 좌석 목록 — FairChain 호출 없음 */
    public SeatListResponseDto getSeats(Long concertId) {
        if (!concertRepository.existsById(concertId)) {
            throw new NotFoundException("공연을 찾을 수 없습니다");
        }
        Map<Long, SeatGrade> grades = seatGradeRepository.findByConcertId(concertId).stream()
                .collect(Collectors.toMap(SeatGrade::getId, Function.identity()));
        return new SeatListResponseDto(seatRepository.findByConcertId(concertId).stream()
                .map(seat -> {
                    SeatGrade grade = grades.get(seat.getSeatGradeId());
                    return new SeatDto(seat.getId(), seat.getSeatNumber(), grade.getGradeName(), grade.getPrice(),
                            seat.getStatus());
                })
                .toList());
    }

    /** offerToken이 있으면 재배정 예매(SD-305-01), 없으면 일반 예매(SD-302-01). 업무상 실패는 success=false */
    public ReserveResultDto reserve(ReserveRequestDto dto, Long userId) {
        return dto.offerToken() == null || dto.offerToken().isBlank()
                ? reserveNormal(dto, userId)
                : reserveReallocated(dto, userId);
    }

    private ReserveResultDto reserveNormal(ReserveRequestDto dto, Long userId) {
        VerifyResultDto admission = reservationAccess.verifyAdmission(userId, dto.concertId());
        if (admission == null || admission.status() != VerifyStatus.VERIFIED) {
            return ReserveResultDto.fail(NO_ADMISSION);
        }
        if (ticketRepository.existsActiveByMemberIdAndConcertId(userId, dto.concertId())) {
            return ReserveResultDto.fail(ALREADY_OWNED);
        }
        return transactionTemplate.execute(status -> {
            Optional<Seat> locked = lockSeat(dto.seatId());
            Optional<SeatGrade> grade = locked.flatMap(seat -> gradeOf(seat, dto.concertId()));
            if (grade.isEmpty()) {
                return ReserveResultDto.fail(SEAT_NOT_FOUND);
            }
            Seat seat = locked.get();
            if (seat.getStatus() != SeatStatus.EMPTY) {
                return ReserveResultDto.fail(ALREADY_RESERVED);
            }
            Ticket ticket = purchase(seat, grade.get(), userId);
            return ReserveResultDto.success(ticket.getTicketId());
        });
    }

    /** 재배정 예매 — verifyAdmission은 하지 않는다 */
    private ReserveResultDto reserveReallocated(ReserveRequestDto dto, Long userId) {
        if (!reallocationService.verifyOffer(dto.concertId(), dto.seatId(), userId, dto.offerToken())) {
            return ReserveResultDto.fail(INVALID_OFFER);
        }
        if (ticketRepository.existsActiveByMemberIdAndConcertId(userId, dto.concertId())) {
            return ReserveResultDto.fail(ALREADY_OWNED);
        }
        return transactionTemplate.execute(status -> {
            Optional<Seat> locked = lockSeat(dto.seatId());
            Optional<SeatGrade> grade = locked.flatMap(seat -> gradeOf(seat, dto.concertId()));
            if (grade.isEmpty()) {
                return ReserveResultDto.fail(SEAT_NOT_FOUND);
            }
            Seat seat = locked.get();
            if (seat.getStatus() != SeatStatus.CANCELLED) {
                return ReserveResultDto.fail(NOT_REALLOCATING);
            }
            // 잠근 뒤 다시 확인 — 그 사이 만료됐을 수 있다
            Optional<ReallocationOffer> offer = reallocationService.findValidOffer(
                    dto.concertId(), dto.seatId(), userId, dto.offerToken());
            if (offer.isEmpty()) {
                return ReserveResultDto.fail(INVALID_OFFER);
            }
            Ticket ticket = purchase(seat, grade.get(), userId);
            reallocationService.acceptOffer(offer.get());
            return ReserveResultDto.success(ticket.getTicketId());
        });
    }

    /** 좌석 예약 → 티켓 → 주문(모의 결제) → PURCHASE 기록 Outbox. 트랜잭션 안에서 호출 */
    private Ticket purchase(Seat seat, SeatGrade grade, Long userId) {
        LocalDateTime now = now();
        seat.reserve(userId, now);
        seatRepository.save(seat);
        Ticket ticket = createTicket(seat, grade.getConcertId(), userId, now);
        orderRepository.save(new Order(userId, ticket.getTicketId(), grade.getPrice(), now));
        enqueueRecord(ticket, RecordType.PURCHASE);
        return ticket;
    }

    private Ticket createTicket(Seat seat, Long concertId, Long userId, LocalDateTime now) {
        return ticketRepository.save(new Ticket(userId, seat.getId(), concertId,
                UUID.randomUUID().toString(), seat.getRecordVersion(), now));
    }

    private RecordOutbox enqueueRecord(Ticket ticket, RecordType type) {
        return outboxRepository.save(new RecordOutbox(reservationAccess.buildRecord(ticket, type), now()));
    }

    /**
     * SD-303-01 취소 — 커밋 후 CANCEL 기록을 동기 전송하고 soldOut으로 분기한다.
     * 전송이 실패해도 취소는 성공. 좌석은 CANCELLED로 남고 RecordDispatchService가 재전송 후 같은 분기를 잇는다.
     */
    public boolean cancel(Long ticketId, Long userId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("티켓을 찾을 수 없습니다"));
        if (!ticket.isOwnedBy(userId)) {
            throw new ForbiddenException("본인 티켓이 아닙니다");
        }
        requireCancellable(ticket);

        RecordOutbox outbox = transactionTemplate.execute(status -> {
            Seat seat = seatRepository.findByIdForUpdate(ticket.getSeatId())
                    .orElseThrow(() -> new NotFoundException("좌석을 찾을 수 없습니다"));
            Ticket locked = ticketRepository.findById(ticketId).orElseThrow();
            requireCancellable(locked);
            locked.cancel(seat.nextRecordVersion());
            ticketRepository.save(locked);
            seat.markCancelled();
            seatRepository.save(seat);
            return enqueueRecord(locked, RecordType.CANCEL);
        });

        // Outbox에 저장한 기록을 그대로 보낸다(재전송과 지문 동일)
        RecordRequestDto record = outbox.getPayload();
        try {
            RecordResponseDto response = reservationAccess.registerRecord(record);
            handleCancelResult(record.eventId(), Long.valueOf(record.slotKey()), response.soldOut());
            markSent(outbox.getId());
        } catch (RuntimeException e) {
            log.warn("취소 기록 전송 실패 — Outbox 재전송 대기 ticketId={}: {}", ticketId, e.getMessage());
        }
        return true;
    }

    /** 매진 전 취소면 좌석 개방, 매진 후 취소면 재배정 시작. 이미 처리된 좌석(CANCELLED 아님 · 재배정 중)은 건너뛴다 */
    public void handleCancelResult(Long concertId, Long seatId, boolean soldOut) {
        transactionTemplate.executeWithoutResult(status -> {
            Optional<Seat> locked = seatRepository.findByIdForUpdate(seatId);
            if (locked.isEmpty() || locked.get().getStatus() != SeatStatus.CANCELLED) {
                return;
            }
            Seat seat = locked.get();
            if (!soldOut) {
                seat.open();
                seatRepository.save(seat);
                return;
            }
            if (!reallocationService.isInProgress(seatId)) {
                reallocationService.startReallocation(concertId, seatId);
            }
        });
    }

    private void markSent(Long outboxId) {
        transactionTemplate.executeWithoutResult(status ->
                outboxRepository.findById(outboxId).ifPresent(outbox -> {
                    outbox.markSent();
                    outboxRepository.save(outbox);
                }));
    }

    private void requireCancellable(Ticket ticket) {
        if (ticket.getStatus() != TicketStatus.ACTIVE) {
            throw new BadRequestException("취소할 수 없는 티켓 상태입니다: " + ticket.getStatus());
        }
    }

    private Optional<Seat> lockSeat(Long seatId) {
        return seatRepository.findByIdForUpdate(seatId);
    }

    /** 좌석 등급이 요청한 공연 소속일 때만 */
    private Optional<SeatGrade> gradeOf(Seat seat, Long concertId) {
        return seatGradeRepository.findById(seat.getSeatGradeId())
                .filter(grade -> grade.getConcertId().equals(concertId));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
