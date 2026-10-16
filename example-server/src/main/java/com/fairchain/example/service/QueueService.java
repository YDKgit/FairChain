package com.fairchain.example.service;

import com.fairchain.api.dto.QueueStatusResponseDto;
import com.fairchain.example.common.ForbiddenException;
import com.fairchain.example.common.NotFoundException;
import com.fairchain.example.config.ExampleProperties;
import com.fairchain.example.dto.QueueStatusDto;
import com.fairchain.example.entity.Concert;
import com.fairchain.example.repository.ConcertRepository;
import com.fairchain.example.requester.QueueAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/** C-0203 대기열 서비스 — 대기열 상태는 DB에 저장하지 않고 FairChain 값을 그대로 전달한다 */
@Service
public class QueueService {

    public static final String WAITING = "WAITING";

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    private final QueueAccess queueAccess;
    private final ConcertRepository concertRepository;
    private final ExampleProperties properties;
    private final Clock clock;

    public QueueService(QueueAccess queueAccess, ConcertRepository concertRepository,
                        ExampleProperties properties, Clock clock) {
        this.queueAccess = queueAccess;
        this.concertRepository = concertRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 진입 직후는 WAITING만 알려주고, 정확한 순위는 SSE 첫 이벤트로 받는다 */
    public QueueStatusDto enterQueue(Long memberId, Long eventId) {
        Concert concert = concertRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("공연을 찾을 수 없습니다"));
        if (!concert.isOpened(LocalDateTime.now(clock))) {
            throw new ForbiddenException("예매 오픈 전입니다");
        }
        queueAccess.enter(memberId, eventId);
        return new QueueStatusDto(WAITING, null, null);
    }

    /** FairChain 순번 스트림을 브라우저 SSE로 중계 — WAITING이 아니면 보내고 끝낸다 */
    public SseEmitter subscribeRank(Long memberId, Long eventId) {
        if (!concertRepository.existsById(eventId)) {
            throw new NotFoundException("공연을 찾을 수 없습니다");
        }
        SseEmitter emitter = new SseEmitter(properties.queue().sseTimeout().toMillis());
        AtomicBoolean closed = new AtomicBoolean(false);
        // FairChain 구독을 끊는 수단이 HttpRequester에 없어, 브라우저가 먼저 끊으면 이후 이벤트만 버린다
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(e -> closed.set(true));

        queueAccess.subscribe(memberId, eventId, event -> relay(emitter, closed, event));
        return emitter;
    }

    public void leaveQueue(Long memberId, Long eventId) {
        queueAccess.leave(memberId, eventId);
    }

    public void leaveAllQueues(Long memberId) {
        queueAccess.leaveAll(memberId);
    }

    private void relay(SseEmitter emitter, AtomicBoolean closed, QueueStatusResponseDto event) {
        if (closed.get()) {
            return;
        }
        QueueStatusDto dto = new QueueStatusDto(event.state(), event.rank(), event.waitingCount());
        try {
            emitter.send(SseEmitter.event().data(dto, MediaType.APPLICATION_JSON));
            if (!WAITING.equals(dto.state())) {
                closed.set(true);
                emitter.complete();
            }
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
            log.debug("SSE 전송 실패 (브라우저 연결 종료): {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }
}
