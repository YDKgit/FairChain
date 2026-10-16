package com.fairchain.example.service;

import com.fairchain.api.dto.AnchorListRequestDto;
import com.fairchain.api.dto.AnchorListResponseDto;
import com.fairchain.example.common.ForbiddenException;
import com.fairchain.example.common.NotFoundException;
import com.fairchain.example.dto.TicketDetailResponseDto;
import com.fairchain.example.dto.TicketListResponseDto;
import com.fairchain.example.entity.Concert;
import com.fairchain.example.entity.Seat;
import com.fairchain.example.entity.SeatGrade;
import com.fairchain.example.entity.Ticket;
import com.fairchain.example.entity.VerificationStatus;
import com.fairchain.example.repository.ConcertRepository;
import com.fairchain.example.repository.SeatGradeRepository;
import com.fairchain.example.repository.SeatRepository;
import com.fairchain.example.repository.TicketRepository;
import com.fairchain.example.requester.TicketVerificationAccess;
import org.springframework.stereotype.Service;

import java.util.List;

/** C-0508 티켓 서비스 — 내 티켓을 FairChain 기록과 비교해 보여준다 */
@Service
public class TicketService {

    private final TicketVerificationAccess ticketVerificationAccess;
    private final TicketVerificationService ticketVerificationService;
    private final TicketRepository ticketRepository;
    private final SeatRepository seatRepository;
    private final SeatGradeRepository seatGradeRepository;
    private final ConcertRepository concertRepository;

    public TicketService(TicketVerificationAccess ticketVerificationAccess,
                         TicketVerificationService ticketVerificationService, TicketRepository ticketRepository,
                         SeatRepository seatRepository, SeatGradeRepository seatGradeRepository,
                         ConcertRepository concertRepository) {
        this.ticketVerificationAccess = ticketVerificationAccess;
        this.ticketVerificationService = ticketVerificationService;
        this.ticketRepository = ticketRepository;
        this.seatRepository = seatRepository;
        this.seatGradeRepository = seatGradeRepository;
        this.concertRepository = concertRepository;
    }

    /** SD-501-01 — eventId가 null이면 전체 공연 */
    public TicketListResponseDto getMyTickets(Long userId, Long eventId) {
        AnchorListResponseDto anchors = ticketVerificationAccess.getVerifiedAnchors(new AnchorListRequestDto(userId, eventId));
        List<Ticket> tickets = eventId == null
                ? ticketRepository.findByMemberId(userId)
                : ticketRepository.findByMemberIdAndConcertId(userId, eventId);
        return new TicketListResponseDto(ticketVerificationService.compare(tickets, anchors).stream()
                .map(compared -> compared.ticket() == null
                        ? TicketDetailResponseDto.missingLocal()
                        : toDetail(compared.ticket(), compared.status()))
                .toList());
    }

    /** SD-502 */
    public TicketDetailResponseDto getTicketDetail(Long ticketId, Long userId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("티켓을 찾을 수 없습니다"));
        if (!ticket.isOwnedBy(userId)) {
            throw new ForbiddenException("본인 티켓이 아닙니다");
        }
        AnchorListResponseDto anchors = ticketVerificationAccess.getVerifiedAnchors(
                new AnchorListRequestDto(userId, ticket.getConcertId()));
        VerificationStatus status = ticketVerificationService.determineStatus(
                ticket, ticketVerificationService.findAnchor(ticket, anchors),
                anchors != null && anchors.verificationAvailable());
        return toDetail(ticket, status);
    }

    private TicketDetailResponseDto toDetail(Ticket ticket, VerificationStatus status) {
        Seat seat = seatRepository.findById(ticket.getSeatId()).orElse(null);
        SeatGrade grade = seat == null ? null : seatGradeRepository.findById(seat.getSeatGradeId()).orElse(null);
        Concert concert = concertRepository.findById(ticket.getConcertId()).orElse(null);
        return new TicketDetailResponseDto(
                ticket.getTicketId(),
                concert == null ? null : concert.getTitle(),
                seat == null ? null : seat.getSeatNumber(),
                grade == null ? null : grade.getGradeName(),
                grade == null ? null : grade.getPrice(),
                ticket.getStatus(),
                status,
                ticket.getQrToken());
    }
}
