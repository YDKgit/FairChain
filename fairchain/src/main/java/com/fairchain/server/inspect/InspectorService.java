package com.fairchain.server.inspect;

import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import com.fairchain.server.block.repository.AnchorRecordRepository;
import com.fairchain.server.block.repository.BlockQueueRepository;
import com.fairchain.server.block.requester.NodeLedgerAccess;
import com.fairchain.server.block.requester.NodeLedgerAccess.NodeLedger;
import com.fairchain.server.reservation.entity.Event;
import com.fairchain.server.reservation.repository.AdmissionRepository;
import com.fairchain.server.reservation.repository.EventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 점검 화면용 읽기 전용 조회 — 받은 요청, FairChain DB, 노드(리더 층)의 블록 */
@Service
public class InspectorService {

    private static final Pageable RECENT_ROWS = PageRequest.of(0, 300);
    private static final int BLOCKS = 30;

    private final RequestLogStore requestLogStore;
    private final EventRepository eventRepository;
    private final AnchorRecordRepository anchorRecordRepository;
    private final AdmissionRepository admissionRepository;
    private final BlockQueueRepository blockQueueRepository;
    private final NodeLedgerAccess nodeLedgerAccess;

    public InspectorService(RequestLogStore requestLogStore, EventRepository eventRepository,
                            AnchorRecordRepository anchorRecordRepository, AdmissionRepository admissionRepository,
                            BlockQueueRepository blockQueueRepository, NodeLedgerAccess nodeLedgerAccess) {
        this.requestLogStore = requestLogStore;
        this.eventRepository = eventRepository;
        this.anchorRecordRepository = anchorRecordRepository;
        this.admissionRepository = admissionRepository;
        this.blockQueueRepository = blockQueueRepository;
        this.nodeLedgerAccess = nodeLedgerAccess;
    }

    public record EventRow(Long eventId, Long totalSeats, Long remainingSeats, LocalDateTime soldOutAt) {}

    public record AnchorRow(Long id, Long eventId, Long userId, String type, Long version, String recordKey,
                            String recordHash, String blockState, Long blockIndex, LocalDateTime createdAt) {}

    public record AdmissionRow(Long id, Long eventId, Long userId, Long sequenceNumber, String status,
                               LocalDateTime admittedAt) {}

    public record DbSnapshot(List<EventRow> events, List<AnchorRow> anchorRecords, List<AdmissionRow> admissions,
                             long blockQueueSize) {}

    /**
     * @param id   노드 ID — 꺼져 있는 노드도 해지할 수 있게 응답과 따로 준다 (모르면 null)
     * @param raft 리더 층 Raft 상태 (역할 · 임기 · 제외 · 판정 기록). 예전 노드면 null
     */
    public record NodeView(String id, String url, boolean reachable, NodeLedgerStatusDto status, List<BlockDto> blocks,
                           String error, NodeStatusDto raft) {}

    /** 이 고객사의 요청 + 인증 전에 거절된 요청 (최신순) */
    public List<RequestLogEntry> requests(String clientId) {
        return requestLogStore.recent().stream()
                .filter(e -> e.clientId() == null || e.clientId().equals(clientId))
                .toList();
    }

    @Transactional(readOnly = true)
    public DbSnapshot db(String clientId) {
        List<Event> events = eventRepository.findByClientId(clientId);
        List<Long> eventIds = events.stream().map(Event::getEventId).toList();
        List<AnchorRow> anchors = anchorRecordRepository.findByClientIdOrderByIdDesc(clientId, RECENT_ROWS).stream()
                .map(a -> new AnchorRow(a.getId(), a.getEventId(), a.getUserId(), String.valueOf(a.getType()),
                        a.getVersion(), a.getRecordKey(), a.getRecordHash(), String.valueOf(a.getBlockState()),
                        a.getBlockIndex(), a.getCreatedAt()))
                .toList();
        List<AdmissionRow> admissions = eventIds.isEmpty() ? List.of()
                : admissionRepository.findByEventIdInOrderByIdDesc(eventIds, RECENT_ROWS).stream()
                .map(a -> new AdmissionRow(a.getId(), a.getEventId(), a.getUserId(), a.getSequenceNumber(),
                        String.valueOf(a.getStatus()), a.getAdmittedAt()))
                .toList();
        List<EventRow> eventRows = events.stream()
                .map(e -> new EventRow(e.getEventId(), e.getTotalSeats(), e.getRemainingSeats(), e.getSoldOutAt()))
                .toList();
        return new DbSnapshot(eventRows, anchors, admissions, blockQueueRepository.size());
    }

    /** 노드마다 원장 상태와 최근 블록 (최신순). 노드에 저장된 값을 그대로 보여 준다 */
    public List<NodeView> nodes() {
        List<NodeView> views = new ArrayList<>();
        for (NodeLedger ledger : nodeLedgerAccess.ledgers()) {
            if (!ledger.reachable()) {
                views.add(new NodeView(ledger.id(), ledger.url(), false, null, List.of(), ledger.error(), null));
                continue;
            }
            long height = ledger.height();
            List<BlockDto> newestFirst = new ArrayList<>(height == 0 ? List.of()
                    : nodeLedgerAccess.blocks(ledger.url(), Math.max(1, height - BLOCKS + 1), BLOCKS));
            Collections.reverse(newestFirst);
            views.add(new NodeView(ledger.id(), ledger.url(), true, ledger.status(), newestFirst, null, ledger.raft()));
        }
        return views;
    }
}
