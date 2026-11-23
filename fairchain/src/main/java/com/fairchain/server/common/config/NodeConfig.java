package com.fairchain.server.common.config;

import com.fairchain.api.http.RestHttpRequester;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.server.block.requester.NodeDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 노드 목록과 노드별 HttpRequester (fairchain-api 공용 구현 재사용, 노드 공유 비밀값은 X-Node-Secret 헤더로).
 *
 * fairchain.nodes.urls는 출발점이고, 그 뒤 목록은 {@link NodeDirectory}가 노드에게 물어 맞춘다 — 노드가 추가 · 해지돼도
 * FairChain을 다시 켜지 않는다. fairchain.nodes.refresh-ms(기본 3000)를 0으로 두면 설정의 목록만 쓴다.
 */
@Configuration
public class NodeConfig {

    @Bean(destroyMethod = "stop")
    public NodeDirectory nodeDirectory(FairChainProperties properties,
                                       @Value("${fairchain.nodes.refresh-ms:3000}") long refreshMillis) {
        FairChainProperties.Nodes nodes = properties.nodes();
        NodeDirectory directory = new NodeDirectory(nodes.urls(),
                url -> new RestHttpRequester(url, NodeHeaders.NODE_SECRET, nodes.apiKey(), nodes.timeout()));
        directory.start(refreshMillis);
        return directory;
    }
}
