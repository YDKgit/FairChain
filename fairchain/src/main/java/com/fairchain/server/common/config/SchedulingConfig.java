package com.fairchain.server.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 입장 허용 배치 · 블록 기록 · SSE 전송 주기 작업 (테스트에서는 끔) */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "fairchain.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
