package com.fairchain.server.common.config;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.api.http.RestHttpRequester;
import com.fairchain.nodeapi.NodeHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 노드 3대 각각의 HttpRequester (fairchain-api 공용 구현 재사용, 노드 공유 비밀값은 X-Node-Secret 헤더로) */
@Configuration
public class NodeConfig {

    @Bean
    public List<HttpRequester> nodeRequesters(FairChainProperties properties) {
        FairChainProperties.Nodes nodes = properties.nodes();
        String apiKey = nodes.apiKey() == null || nodes.apiKey().isBlank() ? null : nodes.apiKey();
        return nodes.urls().stream()
                .map(url -> (HttpRequester) new RestHttpRequester(url, NodeHeaders.NODE_SECRET, apiKey, nodes.timeout()))
                .toList();
    }
}
