package com.fairchain.server.queue.service;

import com.fairchain.api.dto.QueueStatusResponseDto;
import com.fairchain.server.common.ErrorCode;
import com.fairchain.server.common.FairChainException;
import com.fairchain.server.common.config.FairChainProperties;
import com.fairchain.server.queue.repository.AdmissionPassRepository;
import com.fairchain.server.queue.repository.QueueRepository;
import com.fairchain.server.queue.repository.QueueRepository.WaitingEntry;
import com.fairchain.server.reservation.entity.Admission;
import com.fairchain.server.reservation.entity.Event;
import com.fairchain.server.reservation.repository.AdmissionRepository;
import com.fairchain.server.reservation.repository.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/** SS-02 대기열 */
@Service
public class FC_QueueService {

    public static final String WAITING = "WAITING";
    public static final String ADMITTED = "ADMITTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String NOT_FOUND = "NOT_FOUND";

    private final QueueRepository queueRepository;
    private final AdmissionPassRepository admissionPassRepository;
    private final AdmissionRepository admissionRepository;
    private final EventRepository eventRepository;
    private final Duration admissionTtl;

    public FC_QueueService(QueueRepository queueRepository, AdmissionPassRepository admissionPassRepository,
                           AdmissionRepository admissionRepository, EventRepository eventRepository,
                           FairChainProperties properties) {
        this.queueRepository = queueRepository;
        this.admissionPassRepository = admissionPassRepository;
        this.admissionRepository = admissionRepository;
        this.eventRepository = eventRepository;
        this.admissionTtl = properties.queue().admissionTtl();
    }

    /** SD-201-02: 이미 대기 중이면 기존 순번, 아니면 새 순번 발급 */
    public Long enter(String clientId, Long userId, Long eventId) {
        checkOwner(clientId, eventId);
        Long existing = queueRepository.zscore(eventId, userId);
        if (existing != null) {
            return existing;
        }
        Long sequenceNumber = queueRepository.nextSequence(eventId);
        queueRepository.zadd(eventId, userId, sequenceNumber);
        return sequenceNumber;
    }

    /** SD-203-02: 순번 소멸 + 입장 권한 회수. Admission 행은 그대로 둔다 (재배정 자격 유지) */
    public void leave(String clientId, Long userId, Long eventId) {
        checkOwner(clientId, eventId);
        queueRepository.zrem(eventId, userId);
        admissionPassRepository.revoke(eventId, userId);
    }

    /** SD-103-02: 로그아웃 — 이 고객사 공연 전체에서 이탈 (userId는 고객사별 값이라 clientId로 범위 한정) */
    @Transactional(readOnly = true)
    public void leaveAll(Long userId, String clientId) {
        for (Event event : eventRepository.findByClientId(clientId)) {
            queueRepository.zrem(event.getEventId(), userId);
            admissionPassRepository.revoke(event.getEventId(), userId);
        }
    }

    /** SD-202-02: 상태는 저장하지 않고 호출될 때마다 계산 */
    @Transactional(readOnly = true)
    public QueueStatusResponseDto getStatus(Long userId, Long eventId) {
        Long rank = queueRepository.zrank(eventId, userId);
        if (rank != null) {
            return new QueueStatusResponseDto(WAITING, rank + 1, queueRepository.zcard(eventId));
        }
        if (admissionPassRepository.exists(eventId, userId)) {
            return new QueueStatusResponseDto(ADMITTED, null, null);
        }
        boolean admittedBefore = admissionRepository.findTopByUserIdAndEventIdOrderByIdDesc(userId, eventId).isPresent();
        return new QueueStatusResponseDto(admittedBefore ? EXPIRED : NOT_FOUND, null, null);
    }

    /** SD-202-03: 순번이 낮은 n명 입장 허용 → Admission(ADMITTED) 기록 + 입장 권한 발급 */
    @Transactional
    public Long admitTopN(Long eventId, int n) {
        List<WaitingEntry> entries = queueRepository.zpopmin(eventId, n);
        for (WaitingEntry entry : entries) {
            admissionRepository.save(Admission.admit(entry.userId(), eventId, entry.sequenceNumber()));
            admissionPassRepository.grant(eventId, entry.userId(), admissionTtl);
        }
        return (long) entries.size();
    }

    @Transactional(readOnly = true)
    public void checkOwner(String clientId, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new FairChainException(ErrorCode.NOT_FOUND, "등록되지 않은 공연입니다: " + eventId));
        if (!event.getClientId().equals(clientId)) {
            throw new FairChainException(ErrorCode.FORBIDDEN, "다른 고객사의 공연입니다");
        }
    }
}
