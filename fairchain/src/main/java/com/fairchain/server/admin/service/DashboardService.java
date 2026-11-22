package com.fairchain.server.admin.service;

import com.fairchain.api.dto.BlockState;
import com.fairchain.server.admin.dto.MetricsDto;
import com.fairchain.server.admin.dto.SummaryDto;
import com.fairchain.server.block.repository.AnchorRecordRepository;
import com.fairchain.server.common.SseSupport;
import com.fairchain.server.queue.repository.MacroBlockCounter;
import com.fairchain.server.queue.repository.QueueRepository;
import com.fairchain.server.reservation.entity.Event;
import com.fairchain.server.reservation.repository.AdmissionRepository;
import com.fairchain.server.reservation.repository.EventRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** SD-605 관제 대시보드 — 1초마다 고객사별 공연 지표(metrics)와 전체 요약(summary)을 SSE로 전송 */
@Service
public class DashboardService {

    private final EventRepository eventRepository;
    private final QueueRepository queueRepository;
    private final MacroBlockCounter macroBlockCounter;
    private final AdmissionRepository admissionRepository;
    private final AnchorRecordRepository anchorRecordRepository;
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public DashboardService(EventRepository eventRepository, QueueRepository queueRepository,
                            MacroBlockCounter macroBlockCounter, AdmissionRepository admissionRepository,
                            AnchorRecordRepository anchorRecordRepository) {
        this.eventRepository = eventRepository;
        this.queueRepository = queueRepository;
        this.macroBlockCounter = macroBlockCounter;
        this.admissionRepository = admissionRepository;
        this.anchorRecordRepository = anchorRecordRepository;
    }

    public SseEmitter register(String clientId) {
        List<SseEmitter> list = emitters.computeIfAbsent(clientId, k -> new CopyOnWriteArrayList<>());
        SseEmitter emitter = SseSupport.openEmitter(list::remove);
        list.add(emitter);
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

    /** 화면 위쪽 카드에 쓰는 고객사 전체 요약 */
    public SummaryDto getSummary(String clientId) {
        List<Long> eventIds = eventRepository.findByClientId(clientId).stream().map(Event::getEventId).toList();
        return new SummaryDto(
                eventIds.isEmpty() ? 0 : admissionRepository.countByEventIdIn(eventIds),
                anchorRecordRepository.countByClientIdAndBlockState(clientId, BlockState.CONFIRMED),
                anchorRecordRepository.countByClientIdAndBlockState(clientId, BlockState.PENDING));
    }

    @Scheduled(fixedRate = 1000)
    public void broadcast() {
        emitters.forEach((clientId, list) -> {
            if (list.isEmpty()) {
                return;
            }
            List<MetricsDto> metrics = getMetricsPerConcert(clientId);
            SummaryDto summary = getSummary(clientId);
            list.removeIf(emitter -> !SseSupport.send(emitter, "metrics", metrics)
                    || !SseSupport.send(emitter, "summary", summary));
        });
    }
}
