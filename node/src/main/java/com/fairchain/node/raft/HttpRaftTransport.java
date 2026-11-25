package com.fairchain.node.raft;

import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.NodeRaftApi;
import io.microraft.RaftEndpoint;
import io.microraft.model.message.RaftMessage;
import io.microraft.transport.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Raft 메시지를 HTTP로 보낸다 (계획 4.2). {@code POST /node/raft/messages} + {@code X-Node-Secret}.
 *
 * MicroRaft의 규칙대로 {@link #send}는 기다리지 않는다: 보내기만 하고 결과는 보지 않으며, 실패는 로그만 남긴다.
 * 메시지가 사라져도 Raft가 다음 하트비트 · 재전송으로 메운다. 응답 메시지는 상대가 같은 경로로 따로 보낸다.
 */
public class HttpRaftTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(HttpRaftTransport.class);

    private final String localId;
    private final MemberRegistry registry;
    private final RaftMessageCodec codec;
    private final String secret;
    private final Duration timeout;
    private final long reachableWindowMillis;
    private final HttpClient http;
    /** 노드 ID → 마지막으로 메시지 전달에 성공한 시각 */
    private final Map<String, Long> lastSuccess = new ConcurrentHashMap<>();
    /** 같은 실패 로그가 하트비트마다 쌓이지 않게: 노드 ID → 지금 실패 중인지 */
    private final Map<String, Boolean> failing = new ConcurrentHashMap<>();
    /** 상대가 410(Gone)으로 답했다 = 이 노드는 해지된 노드다. 인자는 그렇게 알려 준 노드 ID */
    private final Consumer<String> onRemoved;

    public HttpRaftTransport(String localId, MemberRegistry registry, RaftMessageCodec codec, String secret,
                             Duration timeout, Duration reachableWindow, Consumer<String> onRemoved) {
        this.onRemoved = onRemoved;
        this.localId = localId;
        this.registry = registry;
        this.codec = codec;
        this.secret = secret;
        this.timeout = timeout;
        this.reachableWindowMillis = reachableWindow.toMillis();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public void send(RaftEndpoint target, RaftMessage message) {
        String id = String.valueOf(target.getId());
        if (id.equals(localId)) {
            return;
        }
        String url = registry.urlOf(id).orElse(null);
        if (url == null) {
            log.debug("주소를 모르는 노드 {} — 메시지 버림", id);
            return;
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url + NodeRaftApi.MESSAGES))
                    .timeout(timeout)
                    .header(NodeHeaders.NODE_SECRET, secret)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(codec.encode(message)))
                    .build();
        } catch (RuntimeException e) {
            log.warn("Raft 메시지를 만들지 못함 ({} → {}): {}", localId, id, e.toString());
            return;
        }
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            if (error == null && response.statusCode() / 100 == 2) {
                lastSuccess.put(id, System.currentTimeMillis());
                if (failing.remove(id) != null) {
                    log.info("노드 {} 와 Raft 통신 회복", id);
                }
            } else if (error == null && response.statusCode() == 410) {
                onRemoved.accept(id);
            } else if (failing.putIfAbsent(id, Boolean.TRUE) == null) {
                log.info("노드 {} 에 Raft 메시지 전달 실패: {}", id,
                        error != null ? error.getClass().getSimpleName() : "HTTP " + response.statusCode());
            }
        });
    }

    /** 최근(하트비트 시간 제한 안)에 메시지 전달에 성공한 노드면 true — MicroRaft가 스냅샷을 팔로워에게서 받을지 정할 때 쓴다 */
    @Override
    public boolean isReachable(RaftEndpoint endpoint) {
        Long last = lastSuccess.get(String.valueOf(endpoint.getId()));
        return last != null && System.currentTimeMillis() - last <= reachableWindowMillis;
    }
}
