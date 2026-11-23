package com.fairchain.server.inspect;

import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import com.fairchain.server.reservation.entity.Admission;
import com.fairchain.server.reservation.repository.ReallocationRepository;
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
    private static final int CANDIDATE_ROWS = 100;

    private final RequestLogStore requestLogStore;
    private final EventRepository eventRepository;
    private final AnchorRecordRepository anchorRecordRepository;
    private final AdmissionRepository admissionRepository;
    private final BlockQueueRepository blockQueueRepository;
    private final NodeLedgerAccess nodeLedgerAccess;
    private final ReallocationRepository reallocationRepository;

    public InspectorService(RequestLogStore requestLogStore, EventRepository eventRepository,
                            AnchorRecordRepository anchorRecordRepository, AdmissionRepository admissionRepository,
                            BlockQueueRepository blockQueueRepository, NodeLedgerAccess nodeLedgerAccess,
                            ReallocationRepository reallocationRepository) {
        this.requestLogStore = requestLogStore;
        this.eventRepository = eventRepository;
        this.anchorRecordRepository = anchorRecordRepository;
        this.admissionRepository = admissionRepository;
        this.blockQueueRepository = blockQueueRepository;
        this.nodeLedgerAccess = nodeLedgerAccess;
        this.reallocationRepository = reallocationRepository;
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

    /**
     * 재배정 명단의 한 줄.
     *
     * @param rank           명단 순번 = 취소표를 제안받을 차례
     * @param sequenceNumber 대기열에 진입한 순번 (명단은 이 값이 작은 순)
     * @param status         지금 상태 — ADMITTED: 아직 못 삼(차례를 기다림), PURCHASED: 취소표를 삼
     */
    public record CandidateRow(int rank, Long userId, Long sequenceNumber, String status) {}

    /**
     * 공연 하나의 재배정 명단 (매진된 공연만).
     *
     * @param total      명단 전체 인원
     * @param purchased  보여 주는 줄 가운데 취소표를 산 사람 수
     * @param candidates 앞에서부터 CANDIDATE_ROWS명
     */
    public record ReallocationView(Long eventId, LocalDateTime soldOutAt, int total, int purchased, List<CandidateRow> candidates) {}

    /**
     * 이 고객사의 매진된 공연마다, 매진 순간에 만든 재배정 명단과 그 사람들의 지금 상태.
     * 명단은 "대기열을 통과하고도 못 산 사람"을 진입 순번순으로 세운 것이고, 고객사는 취소표가 나오면 이 순서대로 제안한다
     */
    @Transactional(readOnly = true)
    public List<ReallocationView> reallocation(String clientId) {
        List<ReallocationView> views = new ArrayList<>();
        for (Event event : eventRepository.findByClientId(clientId)) {
            if (!event.isSoldOut()) {
                continue;
            }
            Long eventId = event.getEventId();
            List<Long> userIds = reallocationRepository.findCandidates(eventId);
            List<Long> sequences = reallocationRepository.findSequenceNumbers(eventId);
            int shown = Math.min(CANDIDATE_ROWS, userIds.size());
            List<Long> head = userIds.subList(0, shown);
            Map<Long, Admission> latest = new HashMap<>();          // 회원별 최신 입장 기록
            if (!head.isEmpty()) {
                for (Admission a : admissionRepository.findByEventIdAndUserIdIn(eventId, head)) {
                    latest.merge(a.getUserId(), a, (x, y) -> x.getId() > y.getId() ? x : y);
                }
            }
            List<CandidateRow> rows = new ArrayList<>();
            int purchased = 0;
            for (int i = 0; i < shown; i++) {
                Admission a = latest.get(head.get(i));
                String status = a == null ? "ADMITTED" : String.valueOf(a.getStatus());
                if ("PURCHASED".equals(status)) {
                    purchased++;
                }
                rows.add(new CandidateRow(i + 1, head.get(i), i < sequences.size() ? sequences.get(i) : null, status));
            }
            views.add(new ReallocationView(eventId, event.getSoldOutAt(), userIds.size(), purchased, rows));
        }
        views.sort(Comparator.comparing(ReallocationView::soldOutAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return views;
    }

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
