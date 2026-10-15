package com.fairchain.server.reservation.service;

import com.fairchain.api.dto.AdmissionVerifyDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.dto.VerifyStatus;
import com.fairchain.server.block.entity.AnchorRecord;
import com.fairchain.server.block.repository.AnchorRecordRepository;
import com.fairchain.server.block.service.BlockRecordService;
import com.fairchain.server.common.ErrorCode;
import com.fairchain.server.common.FairChainException;
import com.fairchain.server.queue.repository.AdmissionPassRepository;
import com.fairchain.server.reservation.entity.Event;
import com.fairchain.server.reservation.repository.AdmissionRepository;
import com.fairchain.server.reservation.repository.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * SS-03 예매 + 모든 서브시스템 공용 기록 등록.
 * ISSUE(SS-06) · PURCHASE/CANCEL(SS-03) · CHECKIN(SS-04) 모두 registerRecord 하나로 받는다.
 */
@Service
public class ReservationService {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final EventRepository eventRepository;
    private final AdmissionRepository admissionRepository;
    private final AnchorRecordRepository anchorRecordRepository;
    private final AdmissionPassRepository admissionPassRepository;
    private final ReallocationService reallocationService;
    private final BlockRecordService blockRecordService;
    private final TransactionTemplate transactionTemplate;

    public ReservationService(EventRepository eventRepository, AdmissionRepository admissionRepository,
                              AnchorRecordRepository anchorRecordRepository,
                              AdmissionPassRepository admissionPassRepository,
                              ReallocationService reallocationService, BlockRecordService blockRecordService,
                              PlatformTransactionManager transactionManager) {
        this.eventRepository = eventRepository;
        this.admissionRepository = admissionRepository;
        this.anchorRecordRepository = anchorRecordRepository;
        this.admissionPassRepository = admissionPassRepository;
        this.reallocationService = reallocationService;
        this.blockRecordService = blockRecordService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** SD-302-02: 예매 전 입장 권한 확인 */
    public VerifyResultDto verifyAdmission(String clientId, AdmissionVerifyDto dto) {
        ownedEvent(clientId, dto.eventId());
        if (admissionPassRepository.exists(dto.eventId(), dto.userId())) {
            return new VerifyResultDto(VerifyStatus.VERIFIED, null);
        }
        return new VerifyResultDto(VerifyStatus.UNKNOWN, "입장 권한이 없습니다 (만료 또는 미입장)");
    }

    /** SD-302-02 · 303-02 · 305-02 · 401-02 · 604 */
    public RecordResponseDto registerRecord(String clientId, RecordRequestDto dto) {
        validate(dto);

        // 같은 키 · 버전이 이미 있으면 중복 요청 → 처음 응답과 같게
        Optional<AnchorRecord> duplicate = anchorRecordRepository.findByRecordKeyAndVersion(dto.recordKey(), dto.version());
        if (duplicate.isPresent()) {
            if (!duplicate.get().getRecordHash().equals(dto.recordHash())) {
                throw new FairChainException(ErrorCode.CONFLICT, "같은 버전에 다른 지문이 이미 기록되어 있습니다");
            }
            return new RecordResponseDto(ownedEvent(clientId, dto.eventId()).isSoldOut());
        }

        Result result = transactionTemplate.execute(status -> record(clientId, dto));

        // 커밋 뒤에: 블록 기록 대기 줄, 최초 매진이면 재배정 명단
        blockRecordService.enqueueRecord(dto);
        if (result.firstSoldOut()) {
            reallocationService.buildCandidateList(dto.eventId());
        }
        return new RecordResponseDto(result.soldOut());
    }

    private Result record(String clientId, RecordRequestDto dto) {
        checkTransition(clientId, dto, anchorRecordRepository.findLatest(dto.recordKey()).orElse(null));

        boolean firstSoldOut = false;
        Event event;
        switch (dto.type()) {
            case ISSUE -> {
                event = eventRepository.findByIdForUpdate(dto.eventId())
                        .orElseGet(() -> eventRepository.save(new Event(dto.eventId(), clientId)));
                checkOwner(event, clientId);
                event.issueSeat();
            }
            case PURCHASE -> {
                event = lockedEvent(clientId, dto.eventId());
                admissionRepository.findTopByUserIdAndEventIdOrderByIdDesc(dto.userId(), dto.eventId())
                        .ifPresent(admission -> admission.purchase());
                firstSoldOut = event.sell();
            }
            case CANCEL -> {
                event = lockedEvent(clientId, dto.eventId());
                admissionRepository.findTopByUserIdAndEventIdOrderByIdDesc(dto.userId(), dto.eventId())
                        .ifPresent(admission -> admission.cancel());
                event.cancel();
            }
            case CHECKIN -> event = ownedEvent(clientId, dto.eventId());
            default -> throw new FairChainException(ErrorCode.BAD_REQUEST, "알 수 없는 기록 종류");
        }

        anchorRecordRepository.save(AnchorRecord.pending(clientId, dto));
        return new Result(event.isSoldOut(), firstSoldOut);
    }

    private void validate(RecordRequestDto dto) {
        if (dto.type() == null || dto.eventId() == null || dto.recordKey() == null
                || dto.version() == null || dto.recordHash() == null) {
            throw new FairChainException(ErrorCode.BAD_REQUEST, "type · eventId · recordKey · version · recordHash는 필수입니다");
        }
        if (dto.type() != RecordType.ISSUE && dto.userId() == null) {
            throw new FairChainException(ErrorCode.BAD_REQUEST, dto.type() + " 기록에는 userId가 필요합니다");
        }
        if (!SHA256_HEX.matcher(dto.recordKey()).matches() || !SHA256_HEX.matcher(dto.recordHash()).matches()) {
            throw new FairChainException(ErrorCode.BAD_REQUEST, "recordKey · recordHash는 SHA-256 소문자 16진수 64자여야 합니다 (RecordHasher 사용)");
        }
    }

    /**
     * 좌석 하나의 일생: ISSUE v1 → PURCHASE → CANCEL ⇄ PURCHASE(재판매) … → CHECKIN(끝).
     * 버전은 1씩 오른다. 이전 기록의 블록 확정(CONFIRMED)은 기다리지 않는다 — 블록 기록은 대기 줄로 뒤에서 처리.
     */
    private void checkTransition(String clientId, RecordRequestDto dto, AnchorRecord latest) {
        if (latest == null) {
            if (dto.type() != RecordType.ISSUE || dto.version() != 1L) {
                throw new FairChainException(ErrorCode.CONFLICT, "좌석의 첫 기록은 ISSUE v1이어야 합니다");
            }
            return;
        }
        if (!latest.getClientId().equals(clientId)) {
            throw new FairChainException(ErrorCode.FORBIDDEN, "다른 고객사의 좌석 기록입니다");
        }
        if (dto.version() != latest.getVersion() + 1) {
            throw new FairChainException(ErrorCode.CONFLICT,
                    "버전은 최신 기록(v" + latest.getVersion() + ")보다 1 커야 합니다");
        }
        if (!latest.getEventId().equals(dto.eventId())) {
            throw new FairChainException(ErrorCode.CONFLICT, "같은 좌석의 공연이 바뀔 수 없습니다");
        }
        if (!allowed(latest.getType(), dto.type())) {
            throw new FairChainException(ErrorCode.CONFLICT,
                    latest.getType() + " 다음에 " + dto.type() + " 기록은 올 수 없습니다");
        }
    }

    private boolean allowed(RecordType previous, RecordType next) {
        return switch (previous) {
            case ISSUE, CANCEL -> next == RecordType.PURCHASE;
            case PURCHASE -> next == RecordType.CANCEL || next == RecordType.CHECKIN;
            case CHECKIN -> false;
        };
    }

    private Event lockedEvent(String clientId, Long eventId) {
        Event event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new FairChainException(ErrorCode.NOT_FOUND, "등록되지 않은 공연입니다: " + eventId));
        checkOwner(event, clientId);
        return event;
    }

    private Event ownedEvent(String clientId, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new FairChainException(ErrorCode.NOT_FOUND, "등록되지 않은 공연입니다: " + eventId));
        checkOwner(event, clientId);
        return event;
    }

    private void checkOwner(Event event, String clientId) {
        if (!event.getClientId().equals(clientId)) {
            throw new FairChainException(ErrorCode.FORBIDDEN, "다른 고객사의 공연입니다");
        }
    }

    private record Result(boolean soldOut, boolean firstSoldOut) {}
}
