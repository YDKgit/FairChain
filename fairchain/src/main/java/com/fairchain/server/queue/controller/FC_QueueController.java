package com.fairchain.server.queue.controller;

import com.fairchain.api.QueueApi;
import com.fairchain.api.dto.EnterQueueRequestDto;
import com.fairchain.api.dto.EnterQueueResponseDto;
import com.fairchain.api.dto.QueueStatusResponseDto;
import com.fairchain.server.common.ClientContext;
import com.fairchain.server.queue.service.FC_QueueService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class FC_QueueController implements QueueApi {

    private final FC_QueueService queueService;

    /** SSE 연결 (SD-202-02) */
    private final Map<SseEmitter, Subscription> emitters = new ConcurrentHashMap<>();

    public FC_QueueController(FC_QueueService queueService) {
        this.queueService = queueService;
    }

    @Override
    public EnterQueueResponseDto enter(EnterQueueRequestDto dto) {
        Long sequenceNumber = queueService.enter(ClientContext.clientId(), dto.userId(), dto.eventId());
        return new EnterQueueResponseDto(dto.userId(), sequenceNumber);
    }

    @Override
    public QueueStatusResponseDto status(Long userId, Long eventId) {
        queueService.checkOwner(ClientContext.clientId(), eventId);
        return queueService.getStatus(userId, eventId);
    }

    @Override
    public SseEmitter subscribe(Long userId, Long eventId) {
        queueService.checkOwner(ClientContext.clientId(), eventId);
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(emitter, new Subscription(userId, eventId));
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, userId, eventId);
        return emitter;
    }

    @Override
    public void leave(Long userId, Long eventId) {
        queueService.leave(ClientContext.clientId(), userId, eventId);
    }

    @Override
    public void leaveAll(Long userId) {
        queueService.leaveAll(userId, ClientContext.clientId());
    }

    /** 1초마다 연결된 회원에게 순번 전송, WAITING이 아니면 연결 종료 */
    @Scheduled(fixedRate = 1000)
    public void pushStatus() {
        emitters.forEach((emitter, sub) -> send(emitter, sub.userId(), sub.eventId()));
    }

    private void send(SseEmitter emitter, Long userId, Long eventId) {
        try {
            QueueStatusResponseDto status = queueService.getStatus(userId, eventId);
            emitter.send(SseEmitter.event().name("status").data(status));
            if (!FC_QueueService.WAITING.equals(status.state())) {
                emitters.remove(emitter);
                emitter.complete();
            }
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
    }

    private record Subscription(Long userId, Long eventId) {}
}
