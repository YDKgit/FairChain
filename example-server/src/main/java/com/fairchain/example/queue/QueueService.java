package com.fairchain.example.queue;

import com.fairchain.example.common.ApiException;
import com.fairchain.example.concert.Concert;
import com.fairchain.example.concert.ConcertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;

/** SS-02 대기열 — 순번 발급 · 입장 허용은 FairChain이 하고, 예제서버는 중계만 한다 */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    private final QueueAccess queueAccess;
    private final ConcertRepository concertRepository;

    public QueueService(QueueAccess queueAccess, ConcertRepository concertRepository) {
        this.queueAccess = queueAccess;
        this.concertRepository = concertRepository;
    }

    /** 예매 오픈 시각 확인 후 FairChain 대기열 진입 */
    public QueueStatusDto enterQueue(Long memberId, Long eventId) {
        Concert concert = concertRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("공연이 없습니다"));
        if (!concert.isOpen(LocalDateTime.now())) {
            throw ApiException.conflict("아직 예매 오픈 전입니다 (" + concert.getEventOpen() + ")");
        }
        queueAccess.enter(memberId, eventId);
        return getStatus(memberId, eventId);
    }

    public QueueStatusDto getStatus(Long memberId, Long eventId) {
        return QueueStatusDto.of(queueAccess.status(memberId, eventId));
    }

    public void leaveQueue(Long memberId, Long eventId) {
        queueAccess.leave(memberId, eventId);
    }

    /** 로그아웃 시 모든 공연 대기열 이탈 — FairChain이 꺼져 있어도 로그아웃은 막지 않는다 */
    public void leaveAllQueues(Long memberId) {
        try {
            queueAccess.leaveAll(memberId);
        } catch (RuntimeException e) {
            log.warn("대기열 전체 이탈 실패 (회원 {}): {}", memberId, e.getMessage());
        }
    }

    /** FairChain의 순번 이벤트(SSE)를 받아 브라우저로 그대로 중계. WAITING이 아니면 연결을 닫는다 */
    public SseEmitter subscribeRank(Long memberId, Long eventId) {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
        queueAccess.subscribe(memberId, eventId, status -> {
            try {
                emitter.send(SseEmitter.event().name("status").data(QueueStatusDto.of(status)));
                if (!"WAITING".equals(status.state())) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
