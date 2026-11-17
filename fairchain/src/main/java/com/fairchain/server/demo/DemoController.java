package com.fairchain.server.demo;

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

    /** 노드 3대 원장 상태 */
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

    public record NodeStatus(int node, boolean up, Long height, String headHash, boolean chainValid) {

        static NodeStatus of(NodeLedger n) {
            return n.reachable()
                    ? new NodeStatus(n.number(), true, n.status().height(), n.status().headHash(), n.status().chainValid())
                    : new NodeStatus(n.number(), false, null, null, false);
        }
    }
}
