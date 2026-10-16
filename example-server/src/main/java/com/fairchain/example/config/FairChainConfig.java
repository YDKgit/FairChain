package com.fairchain.example.config;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.api.http.RestHttpRequester;
import com.fairchain.api.util.RecordHasher;
import com.fairchain.api.util.Sha256RecordHasher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** FairChain 통신 빈 — 실제 HttpRequester는 fairchain-mock 프로필이 아닐 때만 */
@Configuration
public class FairChainConfig {

    @Bean
    @Profile("!fairchain-mock")
    public HttpRequester fairChainHttpRequester(FairChainProperties properties) {
        return new RestHttpRequester(properties.baseUrl(), properties.apiKey(), properties.timeout());
    }

    /** 지문 계산 — mock 프로필에서도 같은 규칙을 쓴다 */
    @Bean
    public RecordHasher recordHasher() {
        return new Sha256RecordHasher();
    }
}
