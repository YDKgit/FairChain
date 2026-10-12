package com.fairchain.server.queue.repository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/**
 * 매크로 차단 카운터 (Redis)
 * - macro:{userId}:{초}      회원별 초당 요청 수 (2초 뒤 삭제)
 * - macro:blocked:{eventId}  공연별 차단 횟수 (관제 대시보드)
 */
@Repository
public class MacroBlockCounter {

    private final StringRedisTemplate redis;

    public MacroBlockCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long increment(Long userId, long second) {
        String key = "macro:" + userId + ":" + second;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(2));
        }
        return count == null ? 0L : count;
    }

    public void incrementBlocked(Long eventId) {
        redis.opsForValue().increment("macro:blocked:" + eventId);
    }

    public long getBlocked(Long eventId) {
        String value = redis.opsForValue().get("macro:blocked:" + eventId);
        return value == null ? 0L : Long.parseLong(value);
    }
}
