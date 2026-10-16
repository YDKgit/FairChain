package com.fairchain.example.controller;

import com.fairchain.example.common.LoginMember;
import com.fairchain.example.dto.ReallocationOfferDto;
import com.fairchain.example.dto.ReserveRequestDto;
import com.fairchain.example.dto.ReserveResultDto;
import com.fairchain.example.dto.SeatListResponseDto;
import com.fairchain.example.service.ReallocationService;
import com.fairchain.example.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** C-0305 예매 (SS-03) */
@RestController
public class ReservationController {

    private final ReservationService reservationService;
    private final ReallocationService reallocationService;

    public ReservationController(ReservationService reservationService, ReallocationService reallocationService) {
        this.reservationService = reservationService;
        this.reallocationService = reallocationService;
    }

    @GetMapping("/concerts/{concertId}/seats")
    public SeatListResponseDto getSeats(@PathVariable("concertId") Long concertId) {
        return reservationService.getSeats(concertId);
    }

    /** 업무상 실패도 200 + success=false */
    @PostMapping("/reservations")
    public ReserveResultDto reserve(@LoginMember Long memberId, @Valid @RequestBody ReserveRequestDto dto) {
        return reservationService.reserve(dto, memberId);
    }

    @PostMapping("/tickets/{ticketId}/cancel")
    public Map<String, Boolean> cancel(@LoginMember Long memberId, @PathVariable("ticketId") Long ticketId) {
        return Map.of("cancelled", reservationService.cancel(ticketId, memberId));
    }

    /** 보완 — 재배정 알림 수신 대용. 제안이 없으면 204 */
    @GetMapping("/reallocation/{concertId}/offer")
    public ResponseEntity<ReallocationOfferDto> getMyOffer(@LoginMember Long memberId,
                                                           @PathVariable("concertId") Long concertId) {
        return reallocationService.getMyOffer(memberId, concertId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
