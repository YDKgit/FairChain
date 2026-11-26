package com.fairchain.server.reservation.service;

import com.fairchain.server.common.ErrorCode;
import com.fairchain.server.common.FairChainException;
import com.fairchain.server.reservation.entity.Event;
import com.fairchain.server.reservation.repository.EventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 공연 조회 + 고객사 소유 확인. 대기열 · 예매 · 재배정 · 시연 조회가 모두 같은 규칙을 쓴다.
 * "등록되지 않은 공연"은 404, "다른 고객사의 공연"은 403.
 */
@Component
public class EventFinder {

    private final EventRepository eventRepository;
    /** 공연 번호 → 고객사 (checkOwner가 쓴다) */
    private final Map<Long, String> owners = new ConcurrentHashMap<>();

    public EventFinder(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** 이 고객사의 공연이면 돌려주고, 아니면 예외 */
    @Transactional(readOnly = true)
    public Event owned(String clientId, Long eventId) {
        return checkOwner(clientId, eventRepository.findById(eventId).orElseThrow(() -> notFound(eventId)));
    }

    /** 남은 좌석을 바꿀 때 — 행 잠금(SELECT … FOR UPDATE) 상태로 돌려준다. 트랜잭션 안에서만 부를 것 */
    public Event ownedForUpdate(String clientId, Long eventId) {
        return checkOwner(clientId, eventRepository.findByIdForUpdate(eventId).orElseThrow(() -> notFound(eventId)));
    }

    /**
     * 공연 객체가 필요 없고 소유 확인만 할 때. 공연의 고객사는 바뀌지 않고 공연은 지우지 않으므로 한 번 읽은 값을 기억한다 —
     * 대기 순번 확인처럼 수천 명이 주기적으로 부르는 요청이 매번 DB를 읽지 않게 (트랜잭션도 열지 않는다).
     */
    public void checkOwner(String clientId, Long eventId) {
        String owner = owners.get(eventId);
        if (owner == null) {
            owner = eventRepository.findById(eventId).orElseThrow(() -> notFound(eventId)).getClientId();
            owners.put(eventId, owner);
        }
        if (!owner.equals(clientId)) {
            throw new FairChainException(ErrorCode.FORBIDDEN, "다른 고객사의 공연입니다");
        }
    }

    public Event checkOwner(String clientId, Event event) {
        if (!event.getClientId().equals(clientId)) {
            throw new FairChainException(ErrorCode.FORBIDDEN, "다른 고객사의 공연입니다");
        }
        return event;
    }

    private static FairChainException notFound(Long eventId) {
        return new FairChainException(ErrorCode.NOT_FOUND, "등록되지 않은 공연입니다: " + eventId);
    }
}
