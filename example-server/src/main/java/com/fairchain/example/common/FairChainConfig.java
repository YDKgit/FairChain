package com.fairchain.example.common;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.api.http.RestHttpRequester;
import com.fairchain.api.util.RecordHasher;
import com.fairchain.api.util.Sha256RecordHasher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** FairChain 호출은 fairchain-api의 공용 구현을 그대로 쓴다 (모든 요청에 X-API-KEY 헤더) */
@Configuration
public class FairChainConfig {

    @Bean
    public HttpRequester fairChainRequester(ExampleProperties properties) {
        ExampleProperties.FairChain fairChain = properties.fairchain();
        String apiKey = fairChain.apiKey() == null || fairChain.apiKey().isBlank() ? null : fairChain.apiKey();
        return new RestHttpRequester(fairChain.url(), apiKey, fairChain.timeout());
    }

    @Bean
    public RecordHasher recordHasher() {
        return new Sha256RecordHasher();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
