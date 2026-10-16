package com.fairchain.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * FairChain 연결 설정 (fairchain.*)
 *
 * @param clientId recordKey = hash(clientId, seatId) 계산에 쓰는 고객사 식별값
 */
@ConfigurationProperties("fairchain")
public record FairChainProperties(String baseUrl, String apiKey, String clientId, Duration timeout) {}
