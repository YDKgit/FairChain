package com.fairchain.server.queue.repository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/** 중복 요청 응답 캐시 (Redis) — key = idem:{clientId}:{Idempotency-Key}, 값 = 응답 JSON */
@Repository
public class IdempotencyKeyCache {

    private final StringRedisTemplate redis;

    public IdempotencyKeyCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String find(String key) {
        return redis.opsForValue().get("idem:" + key);
    }

    public void save(String key, String responseJson, Duration ttl) {
        redis.opsForValue().set("idem:" + key, responseJson, ttl);
    }
}
