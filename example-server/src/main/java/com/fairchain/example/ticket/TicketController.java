package com.fairchain.example.ticket;

import com.fairchain.example.common.SessionUser;
import com.fairchain.example.ticket.TicketDtos.TicketDetailResponseDto;
import com.fairchain.example.ticket.TicketDtos.TicketListResponseDto;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping
    public TicketListResponseDto getMyTickets(@RequestParam(required = false) Long eventId, HttpSession session) {
        return ticketService.getMyTickets(SessionUser.memberId(session), eventId);
    }

    @GetMapping("/{ticketId}")
    public TicketDetailResponseDto getTicketDetail(@PathVariable Long ticketId, HttpSession session) {
        return ticketService.getTicketDetail(ticketId, SessionUser.memberId(session));
    }
}
