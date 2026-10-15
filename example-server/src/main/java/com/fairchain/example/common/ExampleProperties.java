package com.fairchain.example.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** application.yml의 example.* 설정 */
@ConfigurationProperties("example")
public record ExampleProperties(
        FairChain fairchain,
        @DefaultValue("3m") Duration offerTtl,
        /** false면 대기열을 거치지 않고 바로 좌석 선택 (입장 권한 확인도 생략) */
        @DefaultValue("true") boolean queueEnabled,
        Seed seed
) {

    /**
     * @param url      FairChain 서버 주소
     * @param apiKey   FairChain에 등록된 고객사 API Key
     * @param clientId 고객사 ID — 좌석 키 = hash(clientId, seatId)
     */
    public record FairChain(String url, String apiKey, String clientId, @DefaultValue("3s") Duration timeout) {}

    /** 개발용 초기 계정 · 더미 공연 (비밀번호가 비어 있으면 그 계정은 만들지 않음) */
    public record Seed(String operatorLoginId, String operatorPassword, String staffLoginId, String staffPassword,
                       boolean concerts) {}
}
