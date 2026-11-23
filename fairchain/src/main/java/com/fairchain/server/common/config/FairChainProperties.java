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

    /**
     * @param urls     처음에 아는 노드 주소 (출발점 — 그 뒤 목록은 노드에게 물어 맞춘다)
     * @param apiKey   노드 공유 비밀값 (노드의 node.secret과 같은 값)
     * @param timeout  노드 호출 제한 시간
     * @param managers 점검 화면에서 노드를 추가 · 해지할 수 있는 운영자 로그인 ID. 비우면 seed 운영자(fairchain.seed.operator-login-id)만
     */
    public record Nodes(List<String> urls, String apiKey, Duration timeout, List<String> managers) {}

    /** 개발용 초기 고객사 · 운영자 계정 (없으면 만들지 않음) */
    public record Seed(String clientId, String apiKey, String operatorLoginId, String operatorPassword) {}
}
