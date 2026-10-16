package com.fairchain.example.requester.mock;

import com.fairchain.api.dto.AnchorListRequestDto;
import com.fairchain.api.dto.AnchorListResponseDto;
import com.fairchain.api.dto.ApiKeyDto;
import com.fairchain.api.dto.BlockState;
import com.fairchain.api.dto.EnterQueueResponseDto;
import com.fairchain.api.dto.QueueStatusResponseDto;
import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifiedAnchorDto;
import com.fairchain.api.dto.VerifyRequestDto;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.dto.VerifyStatus;
import com.fairchain.example.config.ExampleProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * fairchain-mock 프로필용 메모리 FairChain — 가짜 Access 구현들이 공유한다.
 * 대기열은 구독하면 1초 간격으로 WAITING 3회(순위 감소) 후 ADMITTED를 보낸다.
 */
@Component
@Profile("fairchain-mock")
public class InMemoryFairChainStub {

    private static final int WAITING_TICKS = 3;
    private static final String WAITING = "WAITING";
    private static final String ADMITTED = "ADMITTED";
    private static final String NOT_FOUND = "NOT_FOUND";

    /** eventId → (userId → 순번), 진입 순서 유지 */
    private final Map<Long, LinkedHashMap<Long, Long>> queues = new HashMap<>();
    private final Set<String> admitted = new HashSet<>();
    private final Set<String> purchased = new HashSet<>();
    /** recordKey → 최신 기록 */
    private final Map<String, RecordRequestDto> latestRecords = new LinkedHashMap<>();
    private final Map<String, Long> blockIndexes = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "fairchain-mock");
        thread.setDaemon(true);
        return thread;
    });

    private long sequence;
    private long blockIndex;
    private volatile boolean soldOut;

    public InMemoryFairChainStub(ExampleProperties properties) {
        this.soldOut = properties.mock() != null && properties.mock().soldOut();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ── 대기열 ──

    /** 중복 진입이면 기존 순번 (Idempotency-Key 흉내) */
    public synchronized EnterQueueResponseDto enter(Long userId, Long eventId) {
        Long number = queues.computeIfAbsent(eventId, id -> new LinkedHashMap<>())
                .computeIfAbsent(userId, id -> ++sequence);
        return new EnterQueueResponseDto(userId, number);
    }

    public synchronized void leave(Long userId, Long eventId) {
        LinkedHashMap<Long, Long> queue = queues.get(eventId);
        if (queue != null) {
            queue.remove(userId);
        }
        admitted.remove(key(userId, eventId));
    }

    public synchronized void leaveAll(Long userId) {
        for (Map.Entry<Long, LinkedHashMap<Long, Long>> entry : queues.entrySet()) {
            entry.getValue().remove(userId);
            admitted.remove(key(userId, entry.getKey()));
        }
    }

    /** 별도 스레드에서 WAITING(순위 감소) → ADMITTED. 진입하지 않았으면 NOT_FOUND 한 번 */
    public void subscribe(Long userId, Long eventId, Consumer<QueueStatusResponseDto> onEvent) {
        if (!isInQueue(userId, eventId)) {
            scheduler.execute(() -> onEvent.accept(new QueueStatusResponseDto(NOT_FOUND, null, null)));
            return;
        }
        if (isAdmitted(userId, eventId)) {
            scheduler.execute(() -> onEvent.accept(new QueueStatusResponseDto(ADMITTED, 0L, 0L)));
            return;
        }
        for (int tick = 0; tick <= WAITING_TICKS; tick++) {
            long rank = WAITING_TICKS - tick;
            scheduler.schedule(() -> {
                if (rank > 0) {
                    onEvent.accept(new QueueStatusResponseDto(WAITING, rank, rank));
                } else {
                    admit(userId, eventId);
                    onEvent.accept(new QueueStatusResponseDto(ADMITTED, 0L, 0L));
                }
            }, tick, TimeUnit.SECONDS);
        }
    }

    public synchronized boolean isAdmitted(Long userId, Long eventId) {
        return admitted.contains(key(userId, eventId));
    }

    private synchronized boolean isInQueue(Long userId, Long eventId) {
        LinkedHashMap<Long, Long> queue = queues.get(eventId);
        return queue != null && queue.containsKey(userId);
    }

    private synchronized void admit(Long userId, Long eventId) {
        if (isInQueue(userId, eventId)) {
            admitted.add(key(userId, eventId));
        }
    }

    // ── 예매 · 기록 ──

    public VerifyResultDto verifyAdmission(Long userId, Long eventId) {
        return isAdmitted(userId, eventId)
                ? new VerifyResultDto(VerifyStatus.VERIFIED, null)
                : new VerifyResultDto(VerifyStatus.UNKNOWN, "대기열을 통과하지 않음");
    }

    /** 매진 이후의 취소면 soldOut=true (example.mock.sold-out 또는 /mock/sold-out으로 조절) */
    public synchronized RecordResponseDto registerRecord(RecordRequestDto dto) {
        latestRecords.put(dto.recordKey(), dto);
        blockIndexes.put(dto.recordKey(), ++blockIndex);
        if (dto.type() == RecordType.PURCHASE) {
            purchased.add(key(dto.userId(), dto.eventId()));
        }
        return new RecordResponseDto(dto.type() == RecordType.CANCEL && soldOut);
    }

    /** 통과했지만 그 공연 PURCHASE 기록이 없는 회원, 진입 순 */
    public synchronized ReallocationListDto getReallocationCandidates(Long eventId) {
        List<Long> userIds = new ArrayList<>();
        for (Long userId : queues.getOrDefault(eventId, new LinkedHashMap<>()).keySet()) {
            if (admitted.contains(key(userId, eventId)) && !purchased.contains(key(userId, eventId))) {
                userIds.add(userId);
            }
        }
        return new ReallocationListDto(eventId, userIds);
    }

    // ── 검증 ──

    public synchronized VerifyResultDto verify(VerifyRequestDto dto) {
        RecordRequestDto latest = latestRecords.get(dto.recordKey());
        if (latest == null) {
            return new VerifyResultDto(VerifyStatus.UNKNOWN, "기록 없음");
        }
        boolean same = latest.version().equals(dto.version()) && latest.recordHash().equals(dto.recordHash());
        return same
                ? new VerifyResultDto(VerifyStatus.VERIFIED, null)
                : new VerifyResultDto(VerifyStatus.TAMPERED, "최신 기록과 다름");
    }

    /** 회원(+공연)의 좌석 키별 최신 기록을 CONFIRMED · 노드 일치로 */
    public synchronized AnchorListResponseDto getVerifiedAnchors(AnchorListRequestDto dto) {
        List<VerifiedAnchorDto> anchors = latestRecords.values().stream()
                .filter(record -> dto.userId().equals(record.userId()))
                .filter(record -> dto.eventId() == null || dto.eventId().equals(record.eventId()))
                .map(record -> new VerifiedAnchorDto(record.recordKey(), record.version(), record.recordHash(),
                        blockIndexes.get(record.recordKey()), BlockState.CONFIRMED, true))
                .toList();
        return new AnchorListResponseDto(anchors, true);
    }

    public ApiKeyDto getKeys() {
        return new ApiKeyDto("mock-api-key", "mock-dashboard-token");
    }

    // ── 시연 조절 ──

    public void setSoldOut(boolean soldOut) {
        this.soldOut = soldOut;
    }

    public boolean isSoldOut() {
        return soldOut;
    }

    public synchronized List<RecordRequestDto> records() {
        return List.copyOf(latestRecords.values());
    }

    private static String key(Long userId, Long eventId) {
        return userId + ":" + eventId;
    }
}
