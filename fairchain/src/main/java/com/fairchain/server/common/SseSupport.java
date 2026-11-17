package com.fairchain.server.common;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.function.Consumer;

/** SSE 연결 공용 처리 — 끊긴 연결 정리와 전송 실패 처리를 한 곳에서 */
public final class SseSupport {

    private SseSupport() {}

    /** 타임아웃 없는 연결. 완료 · 타임아웃 · 오류 어느 쪽으로 끝나든 onGone에 그 연결을 넘긴다 */
    public static SseEmitter openEmitter(Consumer<SseEmitter> onGone) {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> onGone.accept(emitter));
        emitter.onTimeout(() -> onGone.accept(emitter));
        emitter.onError(e -> onGone.accept(emitter));
        return emitter;
    }

    /** 이벤트 전송. 연결이 이미 끊겼으면 false */
    public static boolean send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }
}
