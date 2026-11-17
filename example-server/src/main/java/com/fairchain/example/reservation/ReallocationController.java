package com.fairchain.example.reservation;

import com.fairchain.example.common.SessionUser;
import com.fairchain.example.reservation.ReallocationDtos.AdminStatusDto;
import com.fairchain.example.reservation.ReallocationDtos.OfferDto;
import com.fairchain.example.reservation.ReallocationDtos.RoundDto;
import com.fairchain.example.reservation.ReallocationDtos.StatusDto;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 취소표 재배정 — 회원용 현황 · 제안, 운영자용 현황 · 수동 진행 (/api/operator/** 는 SessionAuthInterceptor가 운영자 확인) */
@RestController
public class ReallocationController {

    private final ReallocationService reallocationService;
    private final ReallocationQueryService queryService;

    public ReallocationController(ReallocationService reallocationService, ReallocationQueryService queryService) {
        this.reallocationService = reallocationService;
        this.queryService = queryService;
    }

    /** 나에게 와 있는 제안 (알림 대신 화면에서 확인) */
    @GetMapping("/api/reallocation/offers")
    public List<OfferDto> offers(HttpSession session) {
        return queryService.openOffers(SessionUser.memberId(session));
    }

    /** 공연의 재배정 현황 — 로그인했으면 내 순번 · 내 제안 포함 */
    @GetMapping("/api/reallocation/concerts/{concertId}")
    public StatusDto status(@PathVariable Long concertId, HttpSession session) {
        return queryService.status(concertId, SessionUser.memberIdOrNull(session));
    }

    /** 기한 초과 정리 등 한 단계 진행 (화면 새로고침 때 호출해도 됨) */
    @PostMapping("/api/reallocation/concerts/{concertId}/advance")
    public StatusDto advance(@PathVariable Long concertId, HttpSession session) {
        reallocationService.advance(concertId);
        return queryService.status(concertId, SessionUser.memberIdOrNull(session));
    }

    @GetMapping("/api/operator/reallocation/{concertId}")
    public AdminStatusDto adminStatus(@PathVariable Long concertId) {
        return queryService.adminStatus(concertId);
    }

    /** (시연용) 제안 시각을 기다리지 않고 지금 회차 시작 */
    @PostMapping("/api/operator/reallocation/{concertId}/offer-now")
    public RoundDto offerNow(@PathVariable Long concertId) {
        return reallocationService.offerNow(concertId);
    }

    /** (시연용) 남은 취소표를 지금 일반 판매로 */
    @PostMapping("/api/operator/reallocation/{concertId}/release-now")
    public Map<String, Integer> releaseNow(@PathVariable Long concertId) {
        return Map.of("released", reallocationService.releaseNow(concertId));
    }
}
