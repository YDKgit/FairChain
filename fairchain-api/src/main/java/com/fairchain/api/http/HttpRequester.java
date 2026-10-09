package com.fairchain.api.http;

import java.util.function.Consumer;

/** 서버 간 HTTP 전송 — Requester들이 이 인터페이스에 전송을 맡긴다 */
public interface HttpRequester {

    <T> T post(String path, Object body, Class<T> responseType);

    <T> T get(String path, Class<T> responseType);

    void delete(String path);

    /** SSE 구독 — 이벤트가 올 때마다 onEvent 호출 (비동기) */
    <T> void subscribe(String path, Class<T> eventType, Consumer<T> onEvent);
}
