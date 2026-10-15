package com.fairchain.server.demo;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.server.block.entity.AnchorRecord;
import com.fairchain.server.block.repository.AnchorRecordRepository;
import com.fairchain.server.common.ClientContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
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
    private final List<HttpRequester> nodes;

    public DemoController(AnchorRecordRepository anchorRecordRepository, List<HttpRequester> nodeRequesters) {
        this.anchorRecordRepository = anchorRecordRepository;
        this.nodes = nodeRequesters;
    }

    /** 공연의 좌석별 최신 기록 (블록 상태 표시용) */
    @GetMapping("/api/demo/events/{eventId}/records")
    public List<SeatRecord> records(@PathVariable("eventId") Long eventId) {
        Map<String, AnchorRecord> latest = new LinkedHashMap<>();
        for (AnchorRecord r : anchorRecordRepository.findByClientIdAndEventIdOrderByIdAsc(ClientContext.clientId(), eventId)) {
            latest.put(r.getRecordKey(), r);
        }
        return latest.values().stream()
                .map(r -> new SeatRecord(r.getRecordKey(), r.getVersion(), r.getType().name(), r.getUserId(),
                        r.getRecordHash(), r.getBlockState().name(), r.getBlockIndex()))
                .toList();
    }

    /** 노드 3대 원장 상태. 노드 비밀값은 서버에만 있으므로 브라우저 대신 FairChain이 묻는다 */
    @GetMapping("/api/demo/nodes")
    public List<NodeStatus> nodes() {
        List<NodeStatus> result = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            try {
                NodeLedgerStatusDto s = nodes.get(i).get(NodeAuditApi.LEDGER_STATUS, NodeLedgerStatusDto.class);
                result.add(new NodeStatus(i + 1, true, s.height(), s.headHash(), s.chainValid()));
            } catch (RuntimeException e) {
                result.add(new NodeStatus(i + 1, false, null, null, false));
            }
        }
        return result;
    }

    public record SeatRecord(String recordKey, Long version, String type, Long userId,
                             String recordHash, String blockState, Long blockIndex) {}

    public record NodeStatus(int node, boolean up, Long height, String headHash, boolean chainValid) {}
}
