package com.fairchain.server.admin.service;

import com.fairchain.server.admin.dto.MetricsDto;
import com.fairchain.server.queue.repository.MacroBlockCounter;
import com.fairchain.server.queue.repository.QueueRepository;
import com.fairchain.server.reservation.repository.EventRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** SD-605 관제 대시보드 — 1초마다 고객사별 공연 지표를 SSE로 전송 */
@Service
public class DashboardService {

    private final EventRepository eventRepository;
    private final QueueRepository queueRepository;
    private final MacroBlockCounter macroBlockCounter;
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public DashboardService(EventRepository eventRepository, QueueRepository queueRepository,
                            MacroBlockCounter macroBlockCounter) {
        this.eventRepository = eventRepository;
        this.queueRepository = queueRepository;
        this.macroBlockCounter = macroBlockCounter;
    }

    public SseEmitter register(String clientId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> list = emitters.computeIfAbsent(clientId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));
        return emitter;
    }

    public List<MetricsDto> getMetricsPerConcert(String clientId) {
        return eventRepository.findByClientId(clientId).stream()
                .map(event -> new MetricsDto(
                        event.getEventId(),
                        queueRepository.zcard(event.getEventId()),
                        macroBlockCounter.getBlocked(event.getEventId()),
                        event.getRemainingSeats()))
                .toList();
    }

    @Scheduled(fixedRate = 1000)
    public void broadcast() {
        emitters.forEach((clientId, list) -> {
            if (list.isEmpty()) {
                return;
            }
            List<MetricsDto> metrics = getMetricsPerConcert(clientId);
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().name("metrics").data(metrics));
                } catch (IOException | IllegalStateException e) {
                    list.remove(emitter);
                }
            }
        });
    }
}
