package com.fairchain.example.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 화면이 알아야 하는 설정 — 대기열을 거치는지, FairChain 주소(대시보드 · 점검 화면 링크) */
@RestController
public class ConfigController {

    private final ExampleProperties properties;

    public ConfigController(ExampleProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/config")
    public Map<String, Object> config() {
        return Map.of("queueEnabled", properties.queueEnabled(), "fairChainUrl", properties.fairchain().url());
    }
}
