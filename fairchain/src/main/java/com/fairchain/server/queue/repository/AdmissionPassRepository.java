package com.fairchain.server.queue.repository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/** 입장 권한 (Redis TTL) — key = admission:{eventId}:{userId}, TTL이 지나면 자동 삭제 */
@Repository
public class AdmissionPassRepository {

    private final StringRedisTemplate redis;

    public AdmissionPassRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void grant(Long eventId, Long userId, Duration ttl) {
        redis.opsForValue().set(key(eventId, userId), "1", ttl);
    }

    public boolean exists(Long eventId, Long userId) {
        return Boolean.TRUE.equals(redis.hasKey(key(eventId, userId)));
    }

    public void revoke(Long eventId, Long userId) {
        redis.delete(key(eventId, userId));
    }

    private String key(Long eventId, Long userId) {
        return "admission:" + eventId + ":" + userId;
    }
}
