package com.fairchain.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
 * HttpRequester 기본 구현. 모든 요청에 apiKey 헤더를 붙인다.
 * 사용하는 쪽에서 @Bean으로 등록: new RestHttpRequester("http://localhost:8081", apiKey, Duration.ofSeconds(3))
 * 노드 호출처럼 인증키가 없는 곳은 apiKey에 null을 넘긴다.
 */
public class RestHttpRequester implements HttpRequester {

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient sseClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RestHttpRequester(String baseUrl, String apiKey, Duration timeout) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.timeout = timeout;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory);
        if (apiKey != null) {
            builder.defaultHeader(FairChainHeaders.API_KEY, apiKey);
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
    public <T> void subscribe(String path, Class<T> eventType, Consumer<T> onEvent) {
        // SSE는 연결이 오래 유지되므로 읽기 타임아웃 없이 JDK HttpClient로 줄 단위 수신
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "text/event-stream")
                .GET();
        if (apiKey != null) {
            request.header(FairChainHeaders.API_KEY, apiKey);
        }
        sseClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> response.body()
                        .filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring(5).trim())
                        .forEach(data -> onEvent.accept(readJson(data, eventType))));
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
