package com.fairchain.example.service;

import com.fairchain.api.dto.AnchorListResponseDto;
import com.fairchain.api.dto.BlockState;
import com.fairchain.api.dto.VerifiedAnchorDto;
import com.fairchain.example.entity.OutboxStatus;
import com.fairchain.example.entity.Ticket;
import com.fairchain.example.entity.VerificationStatus;
import com.fairchain.example.repository.RecordOutboxRepository;
import com.fairchain.example.requester.RecordFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * C-0509 티켓 목록 검증 — 자기 DB 티켓으로 지문을 다시 계산해 FairChain 기록(anchor)과 비교한다.
 * 설계상 지문 계산을 직접 하는 유일한 Service (RecordFactory 사용).
 */
@Service
public class TicketVerificationService {

    private final RecordFactory recordFactory;
    private final RecordOutboxRepository outboxRepository;

    public TicketVerificationService(RecordFactory recordFactory, RecordOutboxRepository outboxRepository) {
        this.recordFactory = recordFactory;
        this.outboxRepository = outboxRepository;
    }

    /** 판정 결과 — MISSING_LOCAL이면 ticket은 null */
    public record ComparedTicket(Ticket ticket, VerificationStatus status) {}

    /** 로컬 티켓마다 같은 recordKey의 anchor로 판정, 로컬 티켓이 없는 anchor는 MISSING_LOCAL */
    public List<ComparedTicket> compare(List<Ticket> local, AnchorListResponseDto response) {
        Map<String, VerifiedAnchorDto> anchors = byRecordKey(response);
        boolean available = response != null && response.verificationAvailable();
        Set<String> pending = pendingRecords();

        List<ComparedTicket> result = new ArrayList<>();
        Set<String> localKeys = new HashSet<>();
        for (Ticket ticket : local) {
            String recordKey = recordFactory.recordKey(ticket.getSeatId());
            localKeys.add(recordKey);
            result.add(new ComparedTicket(ticket, determineStatus(ticket, anchors.get(recordKey), available, pending)));
        }
        anchors.keySet().stream()
                .filter(recordKey -> !localKeys.contains(recordKey))
                .forEach(recordKey -> result.add(new ComparedTicket(null, VerificationStatus.MISSING_LOCAL)));
        return result;
    }

    public VerificationStatus determineStatus(Ticket ticket, VerifiedAnchorDto anchor) {
        return determineStatus(ticket, anchor, true);
    }

    /** verificationAvailable은 응답 전체 값이라 함께 받는다 */
    public VerificationStatus determineStatus(Ticket ticket, VerifiedAnchorDto anchor, boolean verificationAvailable) {
        return determineStatus(ticket, anchor, verificationAvailable, pendingRecords());
    }

    /** 이 티켓의 recordKey와 같은 anchor */
    public VerifiedAnchorDto findAnchor(Ticket ticket, AnchorListResponseDto response) {
        return byRecordKey(response).get(recordFactory.recordKey(ticket.getSeatId()));
    }

    private VerificationStatus determineStatus(Ticket ticket, VerifiedAnchorDto anchor,
                                               boolean verificationAvailable, Set<String> pending) {
        String recordKey = recordFactory.recordKey(ticket.getSeatId());
        // 1. 비동기 전송 전이면 오탐하지 않도록 PENDING (보완)
        if (pending.contains(pendingKey(recordKey, ticket.getVersion()))) {
            return VerificationStatus.PENDING;
        }
        // 2. FairChain에 기록 없음 → 가짜 의심
        if (anchor == null) {
            return VerificationStatus.UNREGISTERED_LOCAL;
        }
        // 3. 버전 · 지문 불일치
        if (!ticket.getVersion().equals(anchor.version()) || !recordFactory.recordHash(ticket).equals(anchor.recordHash())) {
            return VerificationStatus.TAMPER_SUSPECTED;
        }
        // 4. 블록 확정 전
        if (anchor.blockState() == BlockState.PENDING) {
            return VerificationStatus.PENDING;
        }
        // 5. 노드 검증 불가
        if (!verificationAvailable) {
            return VerificationStatus.UNAVAILABLE;
        }
        // 6. 노드 대조 결과
        return anchor.nodeMatched() ? VerificationStatus.VERIFIED : VerificationStatus.TAMPER_SUSPECTED;
    }

    /** 같은 recordKey가 여럿이면 최신 버전 */
    private Map<String, VerifiedAnchorDto> byRecordKey(AnchorListResponseDto response) {
        if (response == null || response.anchors() == null) {
            return Map.of();
        }
        return response.anchors().stream().collect(Collectors.toMap(
                VerifiedAnchorDto::recordKey, anchor -> anchor,
                (a, b) -> a.version() >= b.version() ? a : b,
                LinkedHashMap::new));
    }

    private Set<String> pendingRecords() {
        return outboxRepository.findByStatus(OutboxStatus.PENDING).stream()
                .map(outbox -> pendingKey(outbox.getPayload().recordKey(), outbox.getPayload().version()))
                .collect(Collectors.toSet());
    }

    private static String pendingKey(String recordKey, Long version) {
        return recordKey + ":" + version;
    }
}
