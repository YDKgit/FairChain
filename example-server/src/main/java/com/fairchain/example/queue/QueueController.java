package com.fairchain.example.queue;

import com.fairchain.example.common.SessionUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/queue/{eventId}")
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping
    public QueueStatusDto enter(@PathVariable Long eventId, HttpSession session) {
        return queueService.enterQueue(SessionUser.memberId(session), eventId);
    }

    @GetMapping("/status")
    public QueueStatusDto status(@PathVariable Long eventId, HttpSession session) {
        return queueService.getStatus(SessionUser.memberId(session), eventId);
    }

    @DeleteMapping
    public ResponseEntity<Void> leave(@PathVariable Long eventId, HttpSession session) {
        queueService.leaveQueue(SessionUser.memberId(session), eventId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/subscribe")
    public SseEmitter subscribe(@PathVariable Long eventId, HttpSession session) {
        return queueService.subscribeRank(SessionUser.memberId(session), eventId);
    }
}
