package com.fairchain.server.queue.repository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 대기열 (Redis)
 * - queue:{eventId}      ZSET  member = userId, score = 순번
 * - queue:seq:{eventId}  INCR  순번 발급
 * - queue:events         SET   대기열이 열린 공연 (입장 허용 배치 대상)
 */
@Repository
public class QueueRepository {

    private static final String EVENTS = "queue:events";

    private final StringRedisTemplate redis;

    public QueueRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Long nextSequence(Long eventId) {
        return redis.opsForValue().increment("queue:seq:" + eventId);
    }

    public void zadd(Long eventId, Long userId, Long sequenceNumber) {
        redis.opsForZSet().add(key(eventId), String.valueOf(userId), sequenceNumber);
        redis.opsForSet().add(EVENTS, String.valueOf(eventId));
    }

    public Long zscore(Long eventId, Long userId) {
        Double score = redis.opsForZSet().score(key(eventId), String.valueOf(userId));
        return score == null ? null : score.longValue();
    }

    /** 0부터 시작하는 등수 (없으면 null) */
    public Long zrank(Long eventId, Long userId) {
        return redis.opsForZSet().rank(key(eventId), String.valueOf(userId));
    }

    public Long zcard(Long eventId) {
        Long size = redis.opsForZSet().zCard(key(eventId));
        return size == null ? 0L : size;
    }

    /** 순번이 낮은 n명을 꺼내면서 제거 (원자적) */
    public List<WaitingEntry> zpopmin(Long eventId, int n) {
        Set<TypedTuple<String>> popped = redis.opsForZSet().popMin(key(eventId), n);
        if (popped == null) {
            return List.of();
        }
        return popped.stream()
                .filter(t -> t.getValue() != null && t.getScore() != null)
                .map(t -> new WaitingEntry(Long.valueOf(t.getValue()), t.getScore().longValue()))
                .toList();
    }

    public void zrem(Long eventId, Long userId) {
        redis.opsForZSet().remove(key(eventId), String.valueOf(userId));
    }

    public List<Long> activeEventIds() {
        Set<String> members = redis.opsForSet().members(EVENTS);
        return members == null ? List.of() : members.stream().filter(Objects::nonNull).map(Long::valueOf).toList();
    }

    private String key(Long eventId) {
        return "queue:" + eventId;
    }

    public record WaitingEntry(Long userId, Long sequenceNumber) {}
}
