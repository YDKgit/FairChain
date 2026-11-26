package com.fairchain.server.queue.service;

import com.fairchain.api.dto.QueueStatusResponseDto;
import com.fairchain.server.common.config.FairChainProperties;
import com.fairchain.server.queue.repository.AdmissionPassRepository;
import com.fairchain.server.queue.repository.QueueRepository;
import com.fairchain.server.queue.repository.QueueRepository.WaitingEntry;
import com.fairchain.server.reservation.entity.Admission;
import com.fairchain.server.reservation.entity.Event;
import com.fairchain.server.reservation.repository.AdmissionRepository;
import com.fairchain.server.reservation.repository.EventRepository;
import com.fairchain.server.reservation.service.EventFinder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/** SS-02 대기열 */
@Service
public class FC_QueueService {

    private final QueueRepository queueRepository;
    private final AdmissionPassRepository admissionPassRepository;
    private final AdmissionRepository admissionRepository;
    private final EventRepository eventRepository;
    private final EventFinder eventFinder;
    private final Duration admissionTtl;
    private final int admitPerSecond;

    public FC_QueueService(QueueRepository queueRepository, AdmissionPassRepository admissionPassRepository,
                           AdmissionRepository admissionRepository, EventRepository eventRepository,
                           EventFinder eventFinder, FairChainProperties properties) {
        this.queueRepository = queueRepository;
        this.admissionPassRepository = admissionPassRepository;
        this.admissionRepository = admissionRepository;
        this.eventRepository = eventRepository;
        this.eventFinder = eventFinder;
        this.admissionTtl = properties.queue().admissionTtl();
        this.admitPerSecond = properties.queue().admitPerSecond();
    }

    /** SD-201-02: 이미 대기 중이면 기존 순번, 아니면 새 순번 발급 */
    public Long enter(String clientId, Long userId, Long eventId) {
        eventFinder.checkOwner(clientId, eventId);
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
        eventFinder.checkOwner(clientId, eventId);
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

    /**
     * SD-202-02: 상태는 저장하지 않고 호출될 때마다 계산.
     * 줄에 서 있는 동안(가장 흔한 경우)은 Redis만 본다 — DB 연결을 잡지 않는다. 대기 인원 수천 명이 주기적으로 묻는 요청이라,
     * 여기서 DB 연결(기본 10개)을 쓰면 그것이 한계가 된다 (2026-11 부하 테스트 1만 5천 명). DB는 줄에 없을 때만 본다.
     */
    public QueueStatusResponseDto getStatus(Long userId, Long eventId) {
        Long rank = queueRepository.zrank(eventId, userId);
        if (rank != null) {
            return new QueueStatusResponseDto(QueueStatusResponseDto.WAITING, rank + 1, queueRepository.zcard(eventId),
                    nextPollMillis(rank + 1, admitPerSecond));
        }
        if (admissionPassRepository.exists(eventId, userId)) {
            return new QueueStatusResponseDto(QueueStatusResponseDto.ADMITTED, null, null);
        }
        boolean admittedBefore = admissionRepository.findTopByUserIdAndEventIdOrderByIdDesc(userId, eventId).isPresent();
        return new QueueStatusResponseDto(
                admittedBefore ? QueueStatusResponseDto.EXPIRED : QueueStatusResponseDto.NOT_FOUND, null, null);
    }

    /**
     * 다음 상태 확인까지 기다릴 시간. 곧 들어갈 사람은 자주, 멀리 있는 사람은 드물게 묻게 한다.
     * <ul>
     *   <li>2초 안에 들어갈 순번(입장 허용 속도 × 2 이내): 1초</li>
     *   <li>10초 안: 2초</li>
     *   <li>그 뒤: 남은 예상 대기 시간의 1/5 (3초 ~ 15초) — 들어갈 때까지 대여섯 번 순번이 바뀌어 보인다</li>
     * </ul>
     * 입장 허용 속도 100명/초, 대기 1만 5천 명이면 초당 상태 확인 요청이 1만 5천 건에서 약 2천 건으로 준다.
     */
    public static long nextPollMillis(long rank, int admitPerSecond) {
        long perSecond = Math.max(1, admitPerSecond);
        if (rank <= perSecond * 2) {
            return 1000;
        }
        if (rank <= perSecond * 10) {
            return 2000;
        }
        long fifthOfWaitMillis = rank * 1000 / perSecond / 5;
        return Math.max(3000, Math.min(15000, fifthOfWaitMillis));
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
}
