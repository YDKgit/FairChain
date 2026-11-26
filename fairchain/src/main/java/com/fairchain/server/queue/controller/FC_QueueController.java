package com.fairchain.server.queue.controller;

import com.fairchain.api.QueueApi;
import com.fairchain.api.dto.EnterQueueRequestDto;
import com.fairchain.api.dto.EnterQueueResponseDto;
import com.fairchain.api.dto.QueueStatusResponseDto;
import com.fairchain.server.common.ClientContext;
import com.fairchain.server.common.SseSupport;
import com.fairchain.server.queue.service.FC_QueueService;
import com.fairchain.server.reservation.service.EventFinder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class FC_QueueController implements QueueApi {

    private final FC_QueueService queueService;
    private final EventFinder eventFinder;

    /** SSE 연결 (SD-202-02) */
    private final Map<SseEmitter, Subscription> emitters = new ConcurrentHashMap<>();

    public FC_QueueController(FC_QueueService queueService, EventFinder eventFinder) {
        this.queueService = queueService;
        this.eventFinder = eventFinder;
    }

    @Override
    public EnterQueueResponseDto enter(EnterQueueRequestDto dto) {
        Long sequenceNumber = queueService.enter(ClientContext.clientId(), dto.userId(), dto.eventId());
        return new EnterQueueResponseDto(dto.userId(), sequenceNumber);
    }

    @Override
    public QueueStatusResponseDto status(Long userId, Long eventId) {
        eventFinder.checkOwner(ClientContext.clientId(), eventId);
        return queueService.getStatus(userId, eventId);
    }

    @Override
    public SseEmitter subscribe(Long userId, Long eventId) {
        eventFinder.checkOwner(ClientContext.clientId(), eventId);
        SseEmitter emitter = SseSupport.openEmitter(emitters::remove);
        Subscription sub = new Subscription(userId, eventId);
        emitters.put(emitter, sub);
        send(emitter, sub);
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

    /**
     * 1초마다 돌며, 다음 확인 시각(nextPollMs)이 된 회원에게만 순번 전송. WAITING이 아니면 연결 종료.
     * 앞쪽 회원은 1초마다, 뒤쪽 회원은 최대 15초마다 받는다 — 상태 확인 요청과 같은 간격.
     */
    @Scheduled(fixedRate = 1000)
    public void pushStatus() {
        long now = System.currentTimeMillis();
        emitters.forEach((emitter, sub) -> {
            if (now >= sub.nextAt) {
                send(emitter, sub);
            }
        });
    }

    private void send(SseEmitter emitter, Subscription sub) {
        QueueStatusResponseDto status = queueService.getStatus(sub.userId, sub.eventId);
        if (!SseSupport.send(emitter, "status", status)) {
            emitters.remove(emitter);
            return;
        }
        if (!status.isWaiting()) {
            emitters.remove(emitter);
            emitter.complete();
            return;
        }
        // 다음 전송은 nextPollMs 뒤. 주기 작업이 1초마다 돌아 조금 늦을 수 있으니 0.2초 일찍 잡는다
        sub.nextAt = System.currentTimeMillis() + (status.nextPollMs() == null ? 1000 : status.nextPollMs()) - 200;
    }

    private static final class Subscription {
        final Long userId;
        final Long eventId;
        volatile long nextAt;

        Subscription(Long userId, Long eventId) {
            this.userId = userId;
            this.eventId = eventId;
        }
    }
}
