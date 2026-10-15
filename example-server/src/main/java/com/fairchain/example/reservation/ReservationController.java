package com.fairchain.example.reservation;

import com.fairchain.example.common.SessionUser;
import com.fairchain.example.reservation.ReservationDtos.OfferDto;
import com.fairchain.example.reservation.ReservationDtos.ReserveRequestDto;
import com.fairchain.example.reservation.ReservationDtos.ReserveResultDto;
import com.fairchain.example.reservation.ReservationDtos.SeatListResponseDto;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/api/concerts/{concertId}/seats")
    public SeatListResponseDto getSeats(@PathVariable Long concertId) {
        return reservationService.getSeats(concertId);
    }

    @PostMapping("/api/reservations")
    public ReserveResultDto reserve(@RequestBody ReserveRequestDto dto, HttpSession session) {
        return reservationService.reserve(dto, SessionUser.memberId(session));
    }

    @PostMapping("/api/tickets/{ticketId}/cancel")
    public Map<String, Boolean> cancel(@PathVariable Long ticketId, HttpSession session) {
        reservationService.cancel(ticketId, SessionUser.memberId(session));
        return Map.of("cancelled", true);
    }

    /** 나에게 온 재배정 제안 (알림 대신 화면에서 확인) */
    @GetMapping("/api/reallocation/offers")
    public List<OfferDto> offers(HttpSession session) {
        return reservationService.openOffers(SessionUser.memberId(session));
    }
}
