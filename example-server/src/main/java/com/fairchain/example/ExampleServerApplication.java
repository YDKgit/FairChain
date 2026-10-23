package com.fairchain.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 예제서버 — 콘서트 예매 사이트. 대기열 · 예매 · 체크인 · 티켓 검증은 FairChain API로 처리한다 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ExampleServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleServerApplication.class, args);
    }
}
