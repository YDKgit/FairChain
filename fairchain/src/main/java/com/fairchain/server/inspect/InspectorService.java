package com.fairchain.server.inspect;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.server.block.repository.AnchorRecordRepository;
import com.fairchain.server.block.repository.BlockQueueRepository;
import com.fairchain.server.common.config.FairChainProperties;
import com.fairchain.server.reservation.entity.Event;
import com.fairchain.server.reservation.repository.AdmissionRepository;
import com.fairchain.server.reservation.repository.EventRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 점검 화면용 읽기 전용 조회 — 받은 요청, FairChain DB, 노드 3대의 블록 */
@Service
public class InspectorService {

    private static final int ROWS = 300;
    private static final int BLOCKS = 30;

    private final RequestLogStore requestLogStore;
    private final EventRepository eventRepository;
    private final AnchorRecordRepository anchorRecordRepository;
    private final AdmissionRepository admissionRepository;
    private final BlockQueueRepository blockQueueRepository;
    private final List<HttpRequester> nodes;
    private final List<String> nodeUrls;

    public InspectorService(RequestLogStore requestLogStore, EventRepository eventRepository,
                            AnchorRecordRepository anchorRecordRepository, AdmissionRepository admissionRepository,
                            BlockQueueRepository blockQueueRepository, List<HttpRequester> nodeRequesters,
                            FairChainProperties properties) {
        this.requestLogStore = requestLogStore;
        this.eventRepository = eventRepository;
        this.anchorRecordRepository = anchorRecordRepository;
        this.admissionRepository = admissionRepository;
        this.blockQueueRepository = blockQueueRepository;
        this.nodes = nodeRequesters;
        this.nodeUrls = properties.nodes().urls();
    }

    public record EventRow(Long eventId, Long totalSeats, Long remainingSeats, LocalDateTime soldOutAt) {}

    public record AnchorRow(Long id, Long eventId, Long userId, String type, Long version, String recordKey,
                            String recordHash, String blockState, Long blockIndex, LocalDateTime createdAt) {}

    public record AdmissionRow(Long id, Long eventId, Long userId, Long sequenceNumber, String status,
                               LocalDateTime admittedAt) {}

    public record DbSnapshot(List<EventRow> events, List<AnchorRow> anchorRecords, List<AdmissionRow> admissions,
                             long blockQueueSize) {}

    public record NodeView(String url, boolean reachable, NodeLedgerStatusDto status, List<BlockDto> blocks,
                           String error) {}

    /** 이 고객사의 요청 + 인증 전에 거절된 요청 (최신순) */
    public List<RequestLogEntry> requests(String clientId) {
        return requestLogStore.recent().stream()
                .filter(e -> e.clientId() == null || e.clientId().equals(clientId))
                .toList();
    }

    @Transactional(readOnly = true)
    public DbSnapshot db(String clientId) {
        List<Event> events = eventRepository.findByClientId(clientId);
        Set<Long> eventIds = events.stream().map(Event::getEventId).collect(Collectors.toSet());
        List<AnchorRow> anchors = anchorRecordRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
                .filter(a -> clientId.equals(a.getClientId()))
                .limit(ROWS)
                .map(a -> new AnchorRow(a.getId(), a.getEventId(), a.getUserId(), String.valueOf(a.getType()),
                        a.getVersion(), a.getRecordKey(), a.getRecordHash(), String.valueOf(a.getBlockState()),
                        a.getBlockIndex(), a.getCreatedAt()))
                .toList();
        List<AdmissionRow> admissions = admissionRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
                .filter(a -> eventIds.contains(a.getEventId()))
                .limit(ROWS)
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
        for (int i = 0; i < nodes.size(); i++) {
            String url = i < nodeUrls.size() ? nodeUrls.get(i) : "node" + (i + 1);
            try {
                HttpRequester node = nodes.get(i);
                NodeLedgerStatusDto status = node.get(NodeAuditApi.LEDGER_STATUS, NodeLedgerStatusDto.class);
                long height = status == null || status.height() == null ? 0 : status.height();
                long from = Math.max(1, height - BLOCKS + 1);
                BlockDto[] blocks = height == 0 ? new BlockDto[0]
                        : node.get(NodeAuditApi.BLOCKS + "?from=" + from + "&limit=" + BLOCKS, BlockDto[].class);
                List<BlockDto> newestFirst = new ArrayList<>(Arrays.asList(blocks == null ? new BlockDto[0] : blocks));
                Collections.reverse(newestFirst);
                views.add(new NodeView(url, true, status, newestFirst, null));
            } catch (RuntimeException e) {
                views.add(new NodeView(url, false, null, List.of(), e.getMessage()));
            }
        }
        return views;
    }
}
