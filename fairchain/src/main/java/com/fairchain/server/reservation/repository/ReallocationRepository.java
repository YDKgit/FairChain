package com.fairchain.server.reservation.repository;

import com.fairchain.server.common.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

/** 재배정 명단 (Redis) — key = reallocation:{eventId}, ZSET member = userId, score = 대기열 진입 순번 (작을수록 먼저) */
@Repository
public class ReallocationRepository {

    private final StringRedisTemplate redis;

    public ReallocationRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @param sequenceNumbers userIds와 같은 순서의 대기열 진입 순번 */
    public void saveCandidates(Long eventId, List<Long> userIds, List<Long> sequenceNumbers) {
        String key = key(eventId);
        redis.delete(key);
        for (int i = 0; i < userIds.size(); i++) {
            redis.opsForZSet().add(key, String.valueOf(userIds.get(i)), sequenceNumbers.get(i));
        }
    }

    public List<Long> findCandidates(Long eventId) {
        Set<String> members = redis.opsForZSet().range(key(eventId), 0, -1);
        return members == null ? List.of() : members.stream().map(Long::valueOf).toList();
    }

    /** findCandidates와 같은 순서의 대기열 진입 순번 */
    public List<Long> findSequenceNumbers(Long eventId) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet().rangeWithScores(key(eventId), 0, -1);
        return tuples == null ? List.of()
                : tuples.stream().map(t -> t.getScore() == null ? 0L : t.getScore().longValue()).toList();
    }

    private static String key(Long eventId) {
        return RedisKeys.reallocation(eventId);
    }
}
