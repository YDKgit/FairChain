package com.fairchain.server.reservation.repository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

/** 재배정 명단 (Redis) — key = reallocation:{eventId}, ZSET member = userId, score = 명단 순서 */
@Repository
public class ReallocationRepository {

    private final StringRedisTemplate redis;

    public ReallocationRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void saveCandidates(Long eventId, List<Long> userIds) {
        String key = key(eventId);
        redis.delete(key);
        for (int i = 0; i < userIds.size(); i++) {
            redis.opsForZSet().add(key, String.valueOf(userIds.get(i)), i);
        }
    }

    public List<Long> findCandidates(Long eventId) {
        Set<String> members = redis.opsForZSet().range(key(eventId), 0, -1);
        return members == null ? List.of() : members.stream().map(Long::valueOf).toList();
    }

    private String key(Long eventId) {
        return "reallocation:" + eventId;
    }
}
