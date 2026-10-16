package com.fairchain.example.controller;

import com.fairchain.example.common.LoginMember;
import com.fairchain.example.dto.TicketDetailResponseDto;
import com.fairchain.example.dto.TicketListResponseDto;
import com.fairchain.example.service.TicketService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** C-0507 티켓 (SS-05) */
@RestController
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /** eventId 생략 시 전체 공연 */
    @GetMapping("/tickets")
    public TicketListResponseDto getMyTickets(@LoginMember Long memberId,
                                              @RequestParam(value = "eventId", required = false) Long eventId) {
        return ticketService.getMyTickets(memberId, eventId);
    }

    @GetMapping("/tickets/{ticketId}")
    public TicketDetailResponseDto getTicketDetail(@LoginMember Long memberId, @PathVariable("ticketId") Long ticketId) {
        return ticketService.getTicketDetail(ticketId, memberId);
    }
}
