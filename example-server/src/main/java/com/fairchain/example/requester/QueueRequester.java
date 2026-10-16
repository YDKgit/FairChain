package com.fairchain.example.requester;

import com.fairchain.api.QueueApi;
import com.fairchain.api.dto.EnterQueueRequestDto;
import com.fairchain.api.dto.EnterQueueResponseDto;
import com.fairchain.api.dto.QueueStatusResponseDto;
import com.fairchain.api.http.HttpRequester;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.function.Consumer;

/**
 * C-0205 대기열 Requester.
 * Idempotency-Key("queue-{memberId}-{eventId}")는 HttpRequester가 헤더를 받지 않아 보내지 못한다 (fairchain-api 요청사항).
 */
@Component
@Profile("!fairchain-mock")
public class QueueRequester implements QueueAccess {

    private final HttpRequester httpRequester;

    public QueueRequester(HttpRequester httpRequester) {
        this.httpRequester = httpRequester;
    }

    @Override
    public EnterQueueResponseDto enter(Long memberId, Long eventId) {
        return FairChainCall.call("대기열 진입", () -> httpRequester.post(
                QueueApi.ENTER, new EnterQueueRequestDto(memberId, eventId), EnterQueueResponseDto.class));
    }

    @Override
    public void leave(Long memberId, Long eventId) {
        FairChainCall.run("대기열 이탈", () -> httpRequester.delete(withUserAndEvent(QueueApi.LEAVE, memberId, eventId)));
    }

    @Override
    public void leaveAll(Long memberId) {
        String path = UriComponentsBuilder.fromPath(QueueApi.LEAVE_ALL).queryParam("userId", memberId).toUriString();
        FairChainCall.run("전체 대기열 이탈", () -> httpRequester.delete(path));
    }

    /** RestHttpRequester.subscribe는 비동기(sendAsync)라 호출 스레드를 붙잡지 않는다. 중간에 끊는 수단은 없다 */
    @Override
    public void subscribe(Long memberId, Long eventId, Consumer<QueueStatusResponseDto> onEvent) {
        String path = withUserAndEvent(QueueApi.SUBSCRIBE, memberId, eventId);
        FairChainCall.run("대기열 구독", () -> httpRequester.subscribe(path, QueueStatusResponseDto.class, onEvent));
    }

    private String withUserAndEvent(String path, Long memberId, Long eventId) {
        return UriComponentsBuilder.fromPath(path)
                .queryParam("userId", memberId)
                .queryParam("eventId", eventId)
                .toUriString();
    }
}
