package com.fairchain.example.queue;

import com.fairchain.api.dto.QueueStatusResponseDto;
import com.fairchain.example.common.ApiException;
import com.fairchain.example.concert.Concert;
import com.fairchain.example.concert.ConcertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * SS-02 대기열 — 순번 발급 · 입장 허용은 FairChain이 하고, 예제서버는 중계만 한다.
 *
 * <p>상태 확인은 FairChain이 알려 준 간격(nextPollMs)마다 오는 것이 정상이다. 그보다 일찍 다시 물으면 FairChain에 가지 않고
 * 직전 답을 돌려준다 (남은 시간만 줄여서). 간격을 지키지 않는 화면 · 프로그램이 수천 개여도 FairChain에 가는 요청은 늘지 않는다.
 * 줄에 서 있을 때(WAITING)만 기억한다 — 입장 허용(ADMITTED)은 바로 알아야 하므로 기억하지 않는다.
 */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);
    /** 간격을 지켜 묻는 화면은 항상 새 답을 받도록, 기억한 답은 간격보다 이만큼 일찍 버린다 */
    static final long EARLY_SLACK_MILLIS = 300;
    private static final long FORGET_AFTER_MILLIS = 60_000;

    private final QueueAccess queueAccess;
    private final ConcertRepository concertRepository;
    private final LongSupplier clock;
    /** "회원:공연" → 직전 상태와 받은 시각 */
    private final Map<String, Recent> recent = new ConcurrentHashMap<>();

    private record Recent(QueueStatusResponseDto status, long fetchedAt) {}

    @Autowired
    public QueueService(QueueAccess queueAccess, ConcertRepository concertRepository) {
        this(queueAccess, concertRepository, System::currentTimeMillis);
    }

    QueueService(QueueAccess queueAccess, ConcertRepository concertRepository, LongSupplier clock) {
        this.queueAccess = queueAccess;
        this.concertRepository = concertRepository;
        this.clock = clock;
    }

    /** 예매 오픈 시각 확인 후 FairChain 대기열 진입 */
    public QueueStatusDto enterQueue(Long memberId, Long eventId) {
        Concert concert = concertRepository.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("공연이 없습니다"));
        if (!concert.isOpen(LocalDateTime.now())) {
            throw ApiException.conflict("아직 예매 오픈 전입니다 (" + concert.getEventOpen() + ")");
        }
        queueAccess.enter(memberId, eventId);
        recent.remove(key(memberId, eventId));
        return getStatus(memberId, eventId);
    }

    public QueueStatusDto getStatus(Long memberId, Long eventId) {
        String key = key(memberId, eventId);
        long now = clock.getAsLong();
        Recent last = recent.get(key);
        if (last != null) {
            long freshAt = last.fetchedAt() + last.status().nextPollMs() - EARLY_SLACK_MILLIS;
            if (now < freshAt) {
                QueueStatusResponseDto s = last.status();       // 너무 일찍 물었다 — 직전 답, 남은 시간만 다시 계산
                return new QueueStatusDto(s.state(), s.rank(), s.waitingCount(), freshAt - now + EARLY_SLACK_MILLIS);
            }
        }
        QueueStatusResponseDto status = queueAccess.status(memberId, eventId);
        if (status.isWaiting() && status.nextPollMs() != null) {
            recent.put(key, new Recent(status, now));
        } else {
            recent.remove(key);
        }
        return QueueStatusDto.of(status);
    }

    public void leaveQueue(Long memberId, Long eventId) {
        recent.remove(key(memberId, eventId));
        queueAccess.leave(memberId, eventId);
    }

    /** 로그아웃 시 모든 공연 대기열 이탈 — FairChain이 꺼져 있어도 로그아웃은 막지 않는다 */
    public void leaveAllQueues(Long memberId) {
        recent.keySet().removeIf(k -> k.startsWith(memberId + ":"));
        try {
            queueAccess.leaveAll(memberId);
        } catch (RuntimeException e) {
            log.warn("대기열 전체 이탈 실패 (회원 {}): {}", memberId, e.getMessage());
        }
    }

    /** 1분 넘게 묻지 않은 회원의 기억은 버린다 (화면을 닫고 떠난 사람) */
    @Scheduled(fixedDelay = FORGET_AFTER_MILLIS)
    public void forgetStale() {
        long now = clock.getAsLong();
        recent.values().removeIf(r -> now - r.fetchedAt() > FORGET_AFTER_MILLIS);
    }

    /** FairChain의 순번 이벤트(SSE)를 받아 브라우저로 그대로 중계. WAITING이 아니면 연결을 닫는다 */
    public SseEmitter subscribeRank(Long memberId, Long eventId) {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
        queueAccess.subscribe(memberId, eventId, status -> {
            try {
                emitter.send(SseEmitter.event().name("status").data(QueueStatusDto.of(status)));
                if (!status.isWaiting()) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        }, emitter::complete);    // FairChain 쪽 연결이 끝나면 브라우저 연결도 닫는다 → 화면이 상태를 직접 물어본다
        return emitter;
    }

    private static String key(Long memberId, Long eventId) {
        return memberId + ":" + eventId;
    }
}
