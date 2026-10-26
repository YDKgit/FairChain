package com.fairchain.server.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 시연용: DB를 메모리(H2)로 쓸 때 Redis에 남은 FairChain 키도 켤 때마다 지운다.
 * 스케줄러(블록 기록 대기 줄 처리)가 돌기 전에 실행된다. FLUSHALL은 쓰지 않음 (다른 데이터가 같은 Redis에 있음).
 */
@Component
@ConditionalOnProperty(name = "fairchain.reset-redis-on-start", havingValue = "true")
public class RedisResetOnStart implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(RedisResetOnStart.class);
    private static final List<String> PATTERNS = List.of(
            "block:queue", "queue:*", "admission:*", "reallocation:*", "macro:*", "idem:*");

    private final StringRedisTemplate redis;

    public RedisResetOnStart(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            List<String> keys = new ArrayList<>();
            for (String pattern : PATTERNS) {
                try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
                    cursor.forEachRemaining(keys::add);
                }
            }
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
            log.info("시작 시 Redis 초기화 — FairChain 키 {}개 삭제", keys.size());
        } catch (RuntimeException e) {     // Redis가 꺼져 있어도 서버는 켠다
            log.warn("시작 시 Redis 초기화 실패 (Redis가 켜져 있는지 확인): {}", e.getMessage());
        }
    }
}
