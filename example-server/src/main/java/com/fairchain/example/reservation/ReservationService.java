package com.fairchain.example.reservation;

import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.dto.VerifyStatus;
import com.fairchain.example.common.ApiException;
import com.fairchain.example.common.ExampleProperties;
import com.fairchain.example.common.TicketFingerprint;
import com.fairchain.example.concert.Concert;
import com.fairchain.example.concert.ConcertRepository;
import com.fairchain.example.concert.SeatGrade;
import com.fairchain.example.concert.SeatGradeRepository;
import com.fairchain.example.reservation.ReservationDtos.OfferDto;
import com.fairchain.example.reservation.ReservationDtos.ReserveRequestDto;
import com.fairchain.example.reservation.ReservationDtos.ReserveResultDto;
import com.fairchain.example.reservation.ReservationDtos.SeatDto;
import com.fairchain.example.reservation.ReservationDtos.SeatListResponseDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SS-03 예매 (C-0306). 좌석 · 티켓은 예제서버 DB에, 판매 · 취소 기록은 Outbox를 거쳐 FairChain으로.
 * 같은 좌석의 동시 요청은 좌석 행 잠금으로 하나만 성공한다.
 */
@Service
public class ReservationService {

    private static final Set<TicketStatus> HOLDING = Set.of(TicketStatus.ACTIVE, TicketStatus.CHECKED_IN);

    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final ConcertRepository concertRepository;
    private final SeatGradeRepository seatGradeRepository;
    private final ReservationAccess reservationAccess;
    private final RecordDispatchService recordDispatchService;
    private final ReallocationService reallocationService;
    private final TicketFingerprint fingerprint;
    private final TransactionTemplate transactionTemplate;
    private final boolean queueEnabled;

    public ReservationService(SeatRepository seatRepository, TicketRepository ticketRepository,
                              ConcertRepository concertRepository, SeatGradeRepository seatGradeRepository,
                              ReservationAccess reservationAccess, RecordDispatchService recordDispatchService,
                              ReallocationService reallocationService, TicketFingerprint fingerprint,
                              PlatformTransactionManager transactionManager, ExampleProperties properties) {
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.concertRepository = concertRepository;
        this.seatGradeRepository = seatGradeRepository;
        this.reservationAccess = reservationAccess;
        this.recordDispatchService = recordDispatchService;
        this.reallocationService = reallocationService;
        this.fingerprint = fingerprint;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.queueEnabled = properties.queueEnabled();
    }

    @Transactional(readOnly = true)
    public SeatListResponseDto getSeats(Long concertId) {
        Map<Long, SeatGrade> grades = seatGradeRepository.findByConcertId(concertId).stream()
                .collect(Collectors.toMap(SeatGrade::getId, Function.identity()));
        List<SeatDto> seats = seatRepository.findByConcertIdOrderByIdAsc(concertId).stream()
                .map(s -> {
                    SeatGrade grade = grades.get(s.getSeatGradeId());
                    return new SeatDto(s.getId(), s.getSeatNumber(), grade == null ? "-" : grade.getGradeName(),
                            grade == null ? null : grade.getPrice(), s.getStatus());
                })
                .toList();
        return new SeatListResponseDto(seats);
    }

    /** 입장 권한(또는 재배정 제안) · 1인 1매 확인 후 좌석 잠금, 티켓 발급, 판매 기록 */
    public ReserveResultDto reserve(ReserveRequestDto dto, Long memberId) {
        if (dto == null || dto.seatId() == null || dto.concertId() == null) {
            throw ApiException.badRequest("좌석과 공연을 선택하세요");
        }
        Concert concert = concertRepository.findById(dto.concertId())
                .orElseThrow(() -> ApiException.notFound("공연이 없습니다"));
        boolean reallocation = dto.offerToken() != null && !dto.offerToken().isBlank();
        ReallocationOffer offer = reallocation
                ? reallocationService.verifyOffer(concert.getId(), dto.seatId(), memberId, dto.offerToken())
                : null;
        if (!reallocation && queueEnabled) {
            VerifyResultDto admission = reservationAccess.verifyAdmission(memberId, concert.getId());
            if (admission.status() != VerifyStatus.VERIFIED) {
                return ReserveResultDto.fail("입장 권한이 없습니다 — 대기열을 통과한 뒤 예매하세요");
            }
        }
        ReserveResultDto result = transactionTemplate.execute(status -> {
            if (ticketRepository.existsByMemberIdAndConcertIdAndStatusIn(memberId, concert.getId(), HOLDING)) {
                return ReserveResultDto.fail("이 공연의 티켓을 이미 가지고 있습니다 (1인 1매)");
            }
            Seat seat = seatRepository.findByIdForUpdate(dto.seatId())
                    .filter(s -> s.getConcertId().equals(concert.getId()))
                    .orElseThrow(() -> ApiException.notFound("좌석이 없습니다"));
            SeatStatus allowed = reallocation ? SeatStatus.CANCELLED : SeatStatus.EMPTY;
            if (seat.getStatus() != allowed) {
                return ReserveResultDto.fail(reallocation ? "재배정 중인 좌석이 아닙니다" : "이미 예약된 좌석입니다");
            }
            long version = seat.reserve(memberId);
            Ticket ticket = ticketRepository.save(Ticket.issue(memberId, seat, version));
            recordDispatchService.enqueue(seat.getId(),
                    fingerprint.record(RecordType.PURCHASE, concert.getId(), seat.getId(), memberId, version));
            if (offer != null) {
                reallocationService.acceptOffer(offer);
            }
            return ReserveResultDto.ok(ticket.getTicketId());
        });
        if (result != null && result.success()) {
            recordDispatchService.dispatchPending();
        }
        return result;
    }

    /** 티켓 취소 → 좌석은 FairChain 응답(매진 여부)을 받을 때까지 재배정 대기 */
    public void cancel(Long ticketId, Long memberId) {
        transactionTemplate.executeWithoutResult(status -> {
            Ticket ticket = ticketRepository.findById(ticketId)
                    .filter(t -> t.getMemberId().equals(memberId))
                    .orElseThrow(() -> ApiException.notFound("내 티켓이 아닙니다"));
            if (ticket.getStatus() != TicketStatus.ACTIVE) {
                throw ApiException.conflict("취소할 수 없는 티켓입니다 (" + ticket.getStatus() + ")");
            }
            Seat seat = seatRepository.findByIdForUpdate(ticket.getSeatId()).orElseThrow();
            long version = seat.release();
            ticket.cancel(version);
            recordDispatchService.enqueue(seat.getId(),
                    fingerprint.record(RecordType.CANCEL, ticket.getConcertId(), seat.getId(), memberId, version));
        });
        recordDispatchService.dispatchPending();
    }

    @Transactional(readOnly = true)
    public List<OfferDto> openOffers(Long memberId) {
        return reallocationService.openOffers(memberId).stream()
                .map(o -> new OfferDto(o.getId(), o.getConcertId(),
                        concertRepository.findById(o.getConcertId()).map(Concert::getTitle).orElse("-"),
                        o.getSeatId(), seatRepository.findById(o.getSeatId()).map(Seat::getSeatNumber).orElse("-"),
                        o.getOfferToken(), o.getExpiresAt()))
                .toList();
    }
}
