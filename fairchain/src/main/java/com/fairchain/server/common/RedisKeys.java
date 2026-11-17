package com.fairchain.server.common;

import java.util.List;

/**
 * FairChain이 Redis에 쓰는 키 이름을 한 곳에 모았다.
 * 키 모양을 바꾸면 여기만 고치면 되고, 시연용 초기화(RedisResetOnStart)도 같은 목록을 쓴다.
 */
public final class RedisKeys {

    /** 블록 기록 대기 줄 (LIST) */
    public static final String BLOCK_QUEUE = "block:queue";
    /** 대기열이 열린 공연 목록 (SET) */
    public static final String QUEUE_EVENTS = "queue:events";

    /** 시연용 초기화 때 지우는 FairChain 키 패턴 전부 */
    public static final List<String> ALL_PATTERNS = List.of(
            BLOCK_QUEUE, "queue:*", "admission:*", "reallocation:*", "macro:*", "idem:*");

    private RedisKeys() {}

    /** 대기열 (ZSET: member = userId, score = 순번) */
    public static String queue(Long eventId) {
        return "queue:" + eventId;
    }

    /** 순번 발급 카운터 (INCR) */
    public static String queueSequence(Long eventId) {
        return "queue:seq:" + eventId;
    }

    /** 입장 권한 (TTL 문자열) */
    public static String admission(Long eventId, Long userId) {
        return "admission:" + eventId + ":" + userId;
    }

    /** 재배정 명단 (ZSET: member = userId, score = 명단 순서) */
    public static String reallocation(Long eventId) {
        return "reallocation:" + eventId;
    }

    /** 회원별 초당 요청 수 (INCR, 2초 뒤 삭제) */
    public static String macroCounter(Long userId, long second) {
        return "macro:" + userId + ":" + second;
    }

    /** 공연별 매크로 차단 횟수 (INCR) */
    public static String macroBlocked(Long eventId) {
        return "macro:blocked:" + eventId;
    }

    /** 중복 요청 응답 캐시 (문자열 JSON, TTL) — key는 "clientId:Idempotency-Key" */
    public static String idempotency(String clientScopedKey) {
        return "idem:" + clientScopedKey;
    }
}
