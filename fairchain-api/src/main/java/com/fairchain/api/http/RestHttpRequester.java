package com.fairchain.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * HttpRequester 기본 구현. 모든 요청에 인증 헤더를 붙인다 (기본 X-API-KEY).
 * 사용하는 쪽에서 @Bean으로 등록: new RestHttpRequester("http://localhost:8081", apiKey, Duration.ofSeconds(3))
 * 노드 호출은 헤더 이름을 바꿔서: new RestHttpRequester(url, NodeHeaders.NODE_SECRET, secret, timeout)
 * 인증키가 없는 곳은 apiKey에 null(또는 빈 문자열)을 넘기면 헤더를 붙이지 않는다.
 */
public class RestHttpRequester implements HttpRequester {

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKeyHeader;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient sseClient;
    /** 모르는 필드는 건너뛴다 — 상대 서버가 필드를 더해도 SSE 수신이 멈추지 않게 */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public RestHttpRequester(String baseUrl, String apiKey, Duration timeout) {
        this(baseUrl, FairChainHeaders.API_KEY, apiKey, timeout);
    }

    public RestHttpRequester(String baseUrl, String apiKeyHeader, String apiKey, Duration timeout) {
        this.baseUrl = baseUrl;
        this.apiKeyHeader = apiKeyHeader;
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.timeout = timeout;

        // 연결을 재사용하는 JDK HttpClient를 쓴다. 요청마다 새 연결을 맺으면 요청이 몰릴 때
        // 운영체제의 임시 포트가 바닥나 "Can't assign requested address"로 실패한다 (6천 명 부하 테스트에서 확인)
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory);
        if (this.apiKey != null) {
            builder.defaultHeader(apiKeyHeader, this.apiKey);
        }
        this.restClient = builder.build();
        this.sseClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public <T> T post(String path, Object body, Class<T> responseType) {
        return restClient.post().uri(path).body(body).retrieve().body(responseType);
    }

    @Override
    public <T> T get(String path, Class<T> responseType) {
        return restClient.get().uri(path).retrieve().body(responseType);
    }

    @Override
    public void delete(String path) {
        restClient.delete().uri(path).retrieve().toBodilessEntity();
    }

    @Override
    public <T> void subscribe(String path, Class<T> eventType, Consumer<T> onEvent, Runnable onClosed) {
        // SSE는 연결이 오래 유지되므로 읽기 타임아웃 없이 JDK HttpClient로 줄 단위 수신
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "text/event-stream")
                .GET();
        if (apiKey != null) {
            request.header(apiKeyHeader, apiKey);
        }
        sseClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    if (response.statusCode() / 100 == 2) {
                        response.body()
                                .filter(line -> line.startsWith("data:"))
                                .map(line -> line.substring(5).trim())
                                .forEach(data -> onEvent.accept(readJson(data, eventType)));
                    }
                })
                // 거절 · 끊김 · 읽기 실패 · 정상 종료 모두 여기로 온다 — 받는 쪽이 계속 기다리지 않게 알린다
                .whenComplete((ok, error) -> onClosed.run());
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
