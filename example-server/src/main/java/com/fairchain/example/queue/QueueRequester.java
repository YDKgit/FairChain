package com.fairchain.example.queue;

import com.fairchain.api.QueueApi;
import com.fairchain.api.dto.EnterQueueRequestDto;
import com.fairchain.api.dto.EnterQueueResponseDto;
import com.fairchain.api.dto.QueueStatusResponseDto;
import com.fairchain.api.http.HttpRequester;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/** 대기열 요청을 FairChain QueueApi 경로로 전송 (C-0205) */
@Component
public class QueueRequester implements QueueAccess {

    private final HttpRequester httpRequester;

    public QueueRequester(HttpRequester httpRequester) {
        this.httpRequester = httpRequester;
    }

    @Override
    public EnterQueueResponseDto enter(Long memberId, Long eventId) {
        return httpRequester.post(QueueApi.ENTER, new EnterQueueRequestDto(memberId, eventId), EnterQueueResponseDto.class);
    }

    @Override
    public QueueStatusResponseDto status(Long memberId, Long eventId) {
        return httpRequester.get(QueueApi.STATUS + query(memberId, eventId), QueueStatusResponseDto.class);
    }

    @Override
    public void leave(Long memberId, Long eventId) {
        httpRequester.delete(QueueApi.LEAVE + query(memberId, eventId));
    }

    @Override
    public void leaveAll(Long memberId) {
        httpRequester.delete(QueueApi.LEAVE_ALL + "?userId=" + memberId);
    }

    @Override
    public void subscribe(Long memberId, Long eventId, Consumer<QueueStatusResponseDto> onEvent) {
        httpRequester.subscribe(QueueApi.SUBSCRIBE + query(memberId, eventId), QueueStatusResponseDto.class, onEvent);
    }

    private static String query(Long memberId, Long eventId) {
        return "?userId=" + memberId + "&eventId=" + eventId;
    }
}
