package com.fairchain.example.controller;

import com.fairchain.api.dto.ApiKeyDto;
import com.fairchain.example.common.LoginOperator;
import com.fairchain.example.common.SessionConst;
import com.fairchain.example.dto.ConcertDetailDto;
import com.fairchain.example.dto.ConcertRegisterDto;
import com.fairchain.example.dto.LoginRequestDto;
import com.fairchain.example.dto.SeatListResponseDto;
import com.fairchain.example.dto.SeatRegisterDto;
import com.fairchain.example.service.ConcertQueryService;
import com.fairchain.example.service.OperatorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** C-0603 운영 관리 (SS-06) — 설계의 concertCrud는 등록 · 수정 · 삭제 핸들러 3개로 나눔 */
@RestController
public class OperatorController {

    private final OperatorService operatorService;
    private final ConcertQueryService concertQueryService;

    public OperatorController(OperatorService operatorService, ConcertQueryService concertQueryService) {
        this.operatorService = operatorService;
        this.concertQueryService = concertQueryService;
    }

    @PostMapping("/admin/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequestDto dto, HttpServletRequest request) {
        var operator = operatorService.login(dto);
        SessionSupport.login(request, SessionConst.LOGIN_OPERATOR_ID, operator.getOperatorId());
        return Map.of("operatorId", operator.getOperatorId(), "name", operator.getName());
    }

    @PostMapping("/admin/logout")
    public void logout(HttpServletRequest request) {
        SessionSupport.invalidate(request);
    }

    @GetMapping("/admin/keys")
    public ApiKeyDto getApiKey(@LoginOperator Long operatorId) {
        return operatorService.getApiKey();
    }

    @PostMapping("/admin/seats")
    public SeatListResponseDto registerSeats(@LoginOperator Long operatorId, @Valid @RequestBody SeatRegisterDto dto) {
        return operatorService.registerSeats(dto);
    }

    @PostMapping("/admin/concerts")
    @ResponseStatus(HttpStatus.CREATED)
    public ConcertDetailDto createConcert(@LoginOperator Long operatorId, @Valid @RequestBody ConcertRegisterDto dto) {
        return concertQueryService.getConcertDetail(operatorService.createConcert(dto));
    }

    @PutMapping("/admin/concerts/{concertId}")
    public ConcertDetailDto updateConcert(@LoginOperator Long operatorId, @PathVariable("concertId") Long concertId,
                                          @Valid @RequestBody ConcertRegisterDto dto) {
        operatorService.updateConcert(concertId, dto);
        return concertQueryService.getConcertDetail(concertId);
    }

    @DeleteMapping("/admin/concerts/{concertId}")
    public void deleteConcert(@LoginOperator Long operatorId, @PathVariable("concertId") Long concertId) {
        operatorService.deleteConcert(concertId);
    }
}
