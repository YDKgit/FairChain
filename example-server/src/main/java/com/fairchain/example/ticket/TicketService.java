package com.fairchain.example.ticket;

import com.fairchain.api.dto.AnchorListRequestDto;
import com.fairchain.api.dto.AnchorListResponseDto;
import com.fairchain.api.dto.VerifiedAnchorDto;
import com.fairchain.example.common.ApiException;
import com.fairchain.example.common.TicketFingerprint;
import com.fairchain.example.concert.Concert;
import com.fairchain.example.concert.ConcertRepository;
import com.fairchain.example.concert.SeatGrade;
import com.fairchain.example.concert.SeatGradeRepository;
import com.fairchain.example.reservation.Seat;
import com.fairchain.example.reservation.SeatRepository;
import com.fairchain.example.reservation.Ticket;
import com.fairchain.example.reservation.TicketRepository;
import com.fairchain.example.reservation.TicketStatus;
import com.fairchain.example.ticket.TicketDtos.TicketDetailResponseDto;
import com.fairchain.example.ticket.TicketDtos.TicketListResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SS-05 내 티켓 — FairChain 검증 목록과 자기 DB 티켓을 비교해 목록을 만든다 (C-0508) */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository ticketRepository;
    private final SeatRepository seatRepository;
    private final SeatGradeRepository seatGradeRepository;
    private final ConcertRepository concertRepository;
    private final TicketVerificationAccess verificationAccess;
    private final TicketVerificationService verificationService;
    private final TicketFingerprint fingerprint;

    public TicketService(TicketRepository ticketRepository, SeatRepository seatRepository,
                         SeatGradeRepository seatGradeRepository, ConcertRepository concertRepository,
                         TicketVerificationAccess verificationAccess, TicketVerificationService verificationService,
                         TicketFingerprint fingerprint) {
        this.ticketRepository = ticketRepository;
        this.seatRepository = seatRepository;
        this.seatGradeRepository = seatGradeRepository;
        this.concertRepository = concertRepository;
        this.verificationAccess = verificationAccess;
        this.verificationService = verificationService;
        this.fingerprint = fingerprint;
    }

    @Transactional(readOnly = true)
    public TicketListResponseDto getMyTickets(Long memberId, Long concertId) {
        List<Ticket> local = concertId == null
                ? ticketRepository.findByMemberIdOrderByTicketIdDesc(memberId)
                : ticketRepository.findByMemberIdAndConcertIdOrderByTicketIdDesc(memberId, concertId);
        AnchorListResponseDto anchors = fetchAnchors(memberId, concertId);
        Map<String, VerifiedAnchorDto> byKey = new HashMap<>();
        anchors.anchors().forEach(a -> byKey.put(a.recordKey(), a));

        List<TicketDetailResponseDto> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Ticket ticket : local) {
            String key = fingerprint.recordKey(ticket.getSeatId());
            VerifiedAnchorDto anchor = byKey.get(key);
            VerificationStatus status = null;
            if (ticket.getStatus() != TicketStatus.CANCELLED) {
                seen.add(key);
                status = verificationService.determineStatus(ticket, anchor, anchors.verificationAvailable());
            }
            result.add(detail(ticket, status, anchor == null ? null : anchor.blockIndex()));
        }
        // FairChain에는 내 티켓으로 남아 있는데 로컬에는 없는 기록
        for (VerifiedAnchorDto anchor : anchors.anchors()) {
            if (!seen.contains(anchor.recordKey())) {
                result.add(new TicketDetailResponseDto(null, null, "-", "-", null, null, null, anchor.version(),
                        VerificationStatus.MISSING_LOCAL, null, null, null, anchor.blockIndex()));
            }
        }
        return new TicketListResponseDto(result, anchors.verificationAvailable());
    }

    @Transactional(readOnly = true)
    public TicketDetailResponseDto getTicketDetail(Long ticketId, Long memberId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .filter(t -> t.getMemberId().equals(memberId))
                .orElseThrow(() -> ApiException.notFound("내 티켓이 아닙니다"));
        return getMyTickets(memberId, ticket.getConcertId()).tickets().stream()
                .filter(t -> ticketId.equals(t.ticketId()))
                .findFirst()
                .orElseGet(() -> detail(ticket, null, null));
    }

    /** FairChain이 응답하지 않으면 로컬 티켓만 보여 준다 (UNAVAILABLE) */
    private AnchorListResponseDto fetchAnchors(Long memberId, Long concertId) {
        try {
            AnchorListResponseDto response = verificationAccess.getVerifiedAnchors(new AnchorListRequestDto(memberId, concertId));
            return response == null ? new AnchorListResponseDto(List.of(), false) : response;
        } catch (RuntimeException e) {
            log.warn("FairChain 티켓 검증 목록 조회 실패: {}", e.getMessage());
            return new AnchorListResponseDto(List.of(), false);
        }
    }

    private TicketDetailResponseDto detail(Ticket ticket, VerificationStatus status, Long blockIndex) {
        Seat seat = seatRepository.findById(ticket.getSeatId()).orElse(null);
        SeatGrade grade = seat == null ? null : seatGradeRepository.findById(seat.getSeatGradeId()).orElse(null);
        String title = concertRepository.findById(ticket.getConcertId()).map(Concert::getTitle).orElse("-");
        return new TicketDetailResponseDto(ticket.getTicketId(), ticket.getConcertId(), title,
                seat == null ? "-" : seat.getSeatNumber(), grade == null ? "-" : grade.getGradeName(),
                grade == null ? null : grade.getPrice(), ticket.getStatus(), ticket.getVersion(), status,
                ticket.getStatus() == TicketStatus.ACTIVE ? ticket.getQrToken() : null,
                ticket.getIssuedAt(), ticket.getCheckedInAt(), blockIndex);
    }
}
