package com.fairchain.server.demo;

import com.fairchain.nodeapi.dto.NodeStatusDto;
import com.fairchain.nodeapi.dto.TeamMemberDto;
import com.fairchain.nodeapi.dto.VerdictDto;
import com.fairchain.server.block.entity.AnchorRecord;
import com.fairchain.server.block.repository.AnchorRecordRepository;
import com.fairchain.server.block.requester.NodeLedgerAccess;
import com.fairchain.server.block.requester.NodeLedgerAccess.NodeLedger;
import com.fairchain.server.common.ClientContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 중간발표 시연 페이지(static/demo.html) 전용 조회. 예제서버 계약(fairchain-api)에는 넣지 않는다.
 * /api/** 아래라 ApiKeyInterceptor를 그대로 거친다.
 */
@RestController
public class DemoController {

    private final AnchorRecordRepository anchorRecordRepository;
    private final NodeLedgerAccess nodeLedgerAccess;

    public DemoController(AnchorRecordRepository anchorRecordRepository, NodeLedgerAccess nodeLedgerAccess) {
        this.anchorRecordRepository = anchorRecordRepository;
        this.nodeLedgerAccess = nodeLedgerAccess;
    }

    /** 공연의 좌석별 최신 기록 (블록 상태 표시용) */
    @GetMapping("/api/demo/events/{eventId}/records")
    public List<SeatRecord> records(@PathVariable("eventId") Long eventId) {
        Map<String, AnchorRecord> latest = new LinkedHashMap<>();
        for (AnchorRecord r : anchorRecordRepository.findByClientIdAndEventIdOrderByIdAsc(ClientContext.clientId(), eventId)) {
            latest.put(r.getRecordKey(), r);
        }
        return latest.values().stream().map(SeatRecord::of).toList();
    }

    /**
     * 노드 원장 상태 + 리더 층 Raft 상태(역할 · 임기 · 빠진 이유 · 판정 기록). 노드 비밀값은 서버에만 있으므로 브라우저 대신
     * FairChain이 묻는다. 이 화면은 조회만 한다 — 노드 추가 · 해지는 점검 화면에서 FairChain 운영자가 한다 (NodeManageController).
     * 목록은 리더 층의 지금 투표 멤버라서 노드가 추가 · 해지되면 같이 바뀐다
     */
    @GetMapping("/api/demo/nodes")
    public List<NodeStatus> nodes() {
        return nodeLedgerAccess.ledgers().stream().map(NodeStatus::of).toList();
    }

    public record SeatRecord(String recordKey, Long version, String type, Long userId,
                             String recordHash, String blockState, Long blockIndex,
                             Long nodeMillis, Long totalMillis) {

        static SeatRecord of(AnchorRecord r) {
            Long totalMillis = r.getConfirmedAt() == null ? null
                    : Duration.between(r.getCreatedAt(), r.getConfirmedAt()).toMillis();
            return new SeatRecord(r.getRecordKey(), r.getVersion(), r.getType().name(), r.getUserId(),
                    r.getRecordHash(), r.getBlockState().name(), r.getBlockIndex(), r.getNodeMillis(), totalMillis);
        }
    }

    /**
     * @param raftRole  LEADER · FOLLOWER · LEARNER · CANDIDATE · NONE(빠짐)
     * @param outReason 빠진 이유 (제외 · 체인 손상 · 복구 중). 참여 중이면 null
     * @param verdicts  판정 기록: #k → EXCLUDED → RESTORED 등
     */
    public record NodeStatus(int node, boolean up, Long height, String headHash, boolean chainValid,
                             String id, String team, String raftRole, Integer term, String leaderId, String outReason,
                             boolean halted, List<String> excluded, List<VerdictDto> verdicts,
                             List<TeamMemberDto> teamMembers) {

        static NodeStatus of(NodeLedger n) {
            if (!n.reachable()) {
                return new NodeStatus(n.number(), false, null, null, false, null, null, null, null, null, null, false,
                        List.of(), List.of(), List.of());
            }
            NodeStatusDto raft = n.raft();
            return new NodeStatus(n.number(), true, n.status().height(), n.status().headHash(), n.status().chainValid(),
                    raft == null ? null : raft.id(), raft == null ? null : raft.team(),
                    raft == null ? null : raft.raftRole(), raft == null ? null : raft.term(),
                    raft == null ? null : raft.leaderId(), raft == null ? null : raft.outReason(),
                    raft != null && raft.halted(), raft == null ? List.of() : raft.excluded(),
                    raft == null ? List.of() : raft.verdicts(), raft == null ? List.of() : raft.teamMembers());
        }
    }
}
