package com.fairchain.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** application.yml의 fairchain.* 설정 */
@ConfigurationProperties("fairchain")
public record FairChainProperties(
        Queue queue,
        RateLimit rateLimit,
        Nodes nodes,
        Seed seed
) {
    public record Queue(int admitPerSecond, Duration admissionTtl) {}

    public record RateLimit(int perSecond, Duration idempotencyTtl) {}

    public record Nodes(List<String> urls, String apiKey, Duration timeout) {}

    /** 개발용 초기 고객사 · 운영자 계정 (없으면 만들지 않음) */
    public record Seed(String clientId, String apiKey, String operatorLoginId, String operatorPassword) {}
}
