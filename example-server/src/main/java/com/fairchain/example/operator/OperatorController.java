package com.fairchain.example.operator;

import com.fairchain.example.common.SessionUser;
import com.fairchain.example.concert.Concert;
import com.fairchain.example.operator.OperatorDtos.ConcertRegisterDto;
import com.fairchain.example.operator.OperatorDtos.KeysDto;
import com.fairchain.example.operator.OperatorDtos.OperatorLoginDto;
import com.fairchain.example.operator.OperatorDtos.SeatRegisterDto;
import com.fairchain.example.reservation.Seat;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/operator")
public class OperatorController {

    private final OperatorService operatorService;

    public OperatorController(OperatorService operatorService) {
        this.operatorService = operatorService;
    }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody OperatorLoginDto dto, HttpSession session) {
        Operator operator = operatorService.login(dto);
        SessionUser.loginOperator(session, operator.getOperatorId());
        return Map.of("name", operator.getName());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        SessionUser.logoutOperator(session);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/keys")
    public KeysDto keys(HttpSession session) {
        SessionUser.operatorId(session);
        return operatorService.getApiKey();
    }

    @PostMapping("/seats")
    public Map<String, Object> registerSeats(@RequestBody SeatRegisterDto dto, HttpSession session) {
        SessionUser.operatorId(session);
        return Map.of("registered", operatorService.registerSeats(dto).stream().map(Seat::getSeatNumber).toList());
    }

    @PostMapping("/concerts")
    public Map<String, Long> createConcert(@RequestBody ConcertRegisterDto dto, HttpSession session) {
        SessionUser.operatorId(session);
        Concert concert = operatorService.createConcert(dto);
        return Map.of("id", concert.getId());
    }

    @PutMapping("/concerts/{concertId}")
    public Map<String, Long> updateConcert(@PathVariable Long concertId, @RequestBody ConcertRegisterDto dto,
                                           HttpSession session) {
        SessionUser.operatorId(session);
        return Map.of("id", operatorService.updateConcert(concertId, dto).getId());
    }

    @DeleteMapping("/concerts/{concertId}")
    public ResponseEntity<Void> deleteConcert(@PathVariable Long concertId, HttpSession session) {
        SessionUser.operatorId(session);
        operatorService.deleteConcert(concertId);
        return ResponseEntity.ok().build();
    }
}
