package com.fairchain.node.service;

import com.fairchain.node.config.NodeSettings;
import com.fairchain.node.raft.MemberRegistry;
import com.fairchain.nodeapi.NodeHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/** 다른 리더 층 노드에 요청을 넘긴다 (팔로워 → 리더 전달). 넘긴 요청에는 FORWARDED 헤더를 달아 다시 넘기지 않게 한다 */
@Component
public class PeerClient {

    private static final Logger log = LoggerFactory.getLogger(PeerClient.class);

    private final MemberRegistry registry;
    private final NodeSettings settings;
    private final RestClient http;

    public PeerClient(MemberRegistry registry, NodeSettings settings) {
        this.registry = registry;
        this.settings = settings;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) settings.forwardTimeout().toMillis());
        factory.setReadTimeout((int) settings.forwardTimeout().toMillis());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** nodeId 노드에 DELETE로 넘긴다 (받은 노드는 다시 넘기지 않는다) */
    public <T> Optional<T> forwardDelete(String nodeId, String path, Class<T> type) {
        String url = registry.urlOf(nodeId).orElse(null);
        if (url == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(http.delete().uri(url + path)
                    .header(NodeHeaders.NODE_SECRET, settings.secret())
                    .header(NodeHeaders.FORWARDED, settings.id())
                    .retrieve().body(type));
        } catch (Exception e) {
            log.debug("노드 {}에 DELETE {} 실패: {}", nodeId, path, e.toString());
            return Optional.empty();
        }
    }

    /** nodeId 노드에 POST로 넘긴다 (받은 노드는 다시 넘기지 않는다). 주소를 모르거나 실패하면 empty */
    public <T> Optional<T> forward(String nodeId, String path, Object body, Class<T> type) {
        return send(nodeId, path, body, type, true);
    }

    /**
     * nodeId 노드에 POST로 보낸다. 받은 노드가 리더가 아니면 리더에게 한 번 넘긴다 — 빠진 노드 · 팀원처럼 리더를 모르는 노드가 쓴다.
     */
    public <T> Optional<T> send(String nodeId, String path, Object body, Class<T> type) {
        return send(nodeId, path, body, type, false);
    }

    private <T> Optional<T> send(String nodeId, String path, Object body, Class<T> type, boolean forwarded) {
        String url = registry.urlOf(nodeId).orElse(null);
        if (url == null) {
            return Optional.empty();
        }
        try {
            var request = http.post().uri(url + path).header(NodeHeaders.NODE_SECRET, settings.secret());
            if (forwarded) {
                request = request.header(NodeHeaders.FORWARDED, settings.id());
            }
            return Optional.ofNullable((body == null ? request : request.body(body)).retrieve().body(type));
        } catch (Exception e) {
            log.debug("노드 {}에 {} 보내기 실패: {}", nodeId, path, e.toString());
            return Optional.empty();
        }
    }

    /** 리더 층 노드에 차례로 보내 처음 받아 준 답 (나 · 제외된 노드는 빼고) */
    public <T> Optional<T> sendToLeaderLayer(String path, Object body, Class<T> type, java.util.function.Predicate<T> accepted) {
        for (var m : registry.all()) {
            if (m.id().equals(settings.id()) || registry.isExcluded(m.id())) {
                continue;
            }
            Optional<T> r = send(m.id(), path, body, type);
            if (r.isPresent() && accepted.test(r.get())) {
                return r;
            }
        }
        return Optional.empty();
    }
}
