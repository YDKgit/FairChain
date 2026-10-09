package com.fairchain.api;

import com.fairchain.api.dto.EnterQueueRequestDto;
import com.fairchain.api.dto.EnterQueueResponseDto;
import com.fairchain.api.dto.QueueStatusResponseDto;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SS-02 대기열 — FC_QueueController가 구현 */
public interface QueueApi {

    String ENTER = "/api/queue/enter";
    String STATUS = "/api/queue/status";
    String SUBSCRIBE = "/api/queue/subscribe";
    String LEAVE = "/api/queue";
    String LEAVE_ALL = "/api/queue/all";

    @PostMapping(ENTER)
    EnterQueueResponseDto enter(@RequestBody EnterQueueRequestDto dto);

    @GetMapping(STATUS)
    QueueStatusResponseDto status(@RequestParam("userId") Long userId, @RequestParam("eventId") Long eventId);

    /** 실시간 순번 (SSE) — 이벤트 본문은 QueueStatusResponseDto */
    @GetMapping(SUBSCRIBE)
    SseEmitter subscribe(@RequestParam("userId") Long userId, @RequestParam("eventId") Long eventId);

    @DeleteMapping(LEAVE)
    void leave(@RequestParam("userId") Long userId, @RequestParam("eventId") Long eventId);

    /** 로그아웃 시 모든 대기열에서 이탈 */
    @DeleteMapping(LEAVE_ALL)
    void leaveAll(@RequestParam("userId") Long userId);
}
