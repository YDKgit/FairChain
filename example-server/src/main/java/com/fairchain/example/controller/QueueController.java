package com.fairchain.example.controller;

import com.fairchain.example.common.LoginMember;
import com.fairchain.example.dto.QueueStatusDto;
import com.fairchain.example.service.QueueService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** C-0202 대기열 (SS-02) */
@RestController
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping("/queue/{eventId}/enter")
    public QueueStatusDto enter(@LoginMember Long memberId, @PathVariable("eventId") Long eventId) {
        return queueService.enterQueue(memberId, eventId);
    }

    /** 이벤트 data = QueueStatusDto. WAITING이 아닌 상태를 보내면 스트림 종료 */
    @GetMapping(value = "/queue/{eventId}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@LoginMember Long memberId, @PathVariable("eventId") Long eventId) {
        return queueService.subscribeRank(memberId, eventId);
    }

    @DeleteMapping("/queue/{eventId}")
    public void leave(@LoginMember Long memberId, @PathVariable("eventId") Long eventId) {
        queueService.leaveQueue(memberId, eventId);
    }
}
