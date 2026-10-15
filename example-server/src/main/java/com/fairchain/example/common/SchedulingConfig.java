package com.fairchain.example.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Outbox 재전송 · 재배정 기한 처리. 테스트에서는 example.scheduling.enabled=false로 끈다 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "example.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
