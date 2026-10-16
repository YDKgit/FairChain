package com.fairchain.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** 예제서버 동작 설정 (example.*) */
@ConfigurationProperties("example")
public record ExampleProperties(
        Cors cors,
        Reallocation reallocation,
        Outbox outbox,
        Queue queue,
        Checkin checkin,
        Mock mock
) {

    public record Cors(List<String> allowedOrigins) {}

    /** @param offerTtl 재배정 제안 응답 기한 */
    public record Reallocation(Duration offerTtl, long expireIntervalMs) {}

    /** @param pendingDelay 생성 후 이 시간이 지난 PENDING만 재전송 (동기 전송과 겹치지 않게) */
    public record Outbox(long dispatchIntervalMs, Duration pendingDelay) {}

    public record Queue(Duration sseTimeout) {}

    /** @param ignoreEventPeriod 시연용: true면 공연 기간 검사 생략 */
    public record Checkin(boolean ignoreEventPeriod) {}

    /** @param soldOut fairchain-mock 프로필 전용: 취소 응답의 soldOut 값 */
    public record Mock(boolean soldOut) {}
}
