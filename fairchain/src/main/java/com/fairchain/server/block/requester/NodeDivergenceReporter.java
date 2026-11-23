package com.fairchain.server.block.requester;

import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.NodeReportApi;
import com.fairchain.nodeapi.dto.DivergenceReportDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import com.fairchain.nodeapi.dto.ReportAckDto;
import com.fairchain.server.block.requester.NodeDirectory.Node;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 노드 답이 다수와 다를 때 리더 층에 "이 노드가 다르다"고 신고만 한다 (노드 구조 미정사항 13 · 14번).
 * 판정 · 제외는 노드(리더 층 리더)가 한다 — 신고가 맞는지는 노드끼리 블록을 대조해 정한다.
 *
 * 신고는 따로 도는 스레드에서 보내 조회 응답을 늦추지 않는다. 같은 노드는 10초에 한 번만 신고한다.
 * 노드 ID는 설정에 적지 않고 노드 목록({@link NodeDirectory})이나 노드의 상태 조회에서 받는다.
 */
@Component
public class NodeDivergenceReporter {

    private static final Logger log = LoggerFactory.getLogger(NodeDivergenceReporter.class);
    private static final long MIN_INTERVAL_MILLIS = 10_000;

    /** 노드 주소 → 마지막 신고 시각 */
    private final Map<String, Long> lastReport = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "node-report");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void stop() {
        sender.shutdownNow();
    }

    /**
     * @param nodes      답을 받은 그 노드 목록 (조회에 쓴 것과 같은 목록)
     * @param suspect    그 목록에서 다수와 다른 답을 한 노드의 순서 (0부터)
     * @param blockIndex 다르게 보인 블록 번호 (모르면 null)
     * @param recordKey  다르게 보인 좌석 키 (모르면 null)
     */
    public void report(List<Node> nodes, int suspect, Long blockIndex, String recordKey) {
        long now = System.currentTimeMillis();
        String key = nodes.get(suspect).url();
        Long last = lastReport.get(key);
        if (last != null && now - last < MIN_INTERVAL_MILLIS) {
            return;
        }
        lastReport.put(key, now);
        try {
            sender.execute(() -> send(nodes, suspect, blockIndex, recordKey));
        } catch (RuntimeException ignored) {
            // 끄는 중
        }
    }

    private void send(List<Node> nodes, int suspect, Long blockIndex, String recordKey) {
        String suspectId = idOf(nodes.get(suspect));
        if (suspectId == null) {
            log.warn("노드 {}의 ID를 몰라 신고하지 못함", nodes.get(suspect).url());
            return;
        }
        DivergenceReportDto report = new DivergenceReportDto(suspectId, blockIndex, recordKey, "fairchain");
        for (int i = 0; i < nodes.size(); i++) {
            if (i == suspect) {
                continue;
            }
            try {
                ReportAckDto ack = nodes.get(i).http().post(NodeReportApi.REPORTS, report, ReportAckDto.class);
                if (ack != null && ack.accepted()) {
                    log.info("노드 {} 신고 → 판정 {}{}", suspectId, ack.reason(),
                            ack.verdict() == null ? "" : " (블록 #" + ack.verdict().blockIndex() + " " + ack.verdict().action() + ")");
                    return;
                }
            } catch (RuntimeException e) {
                log.debug("노드 {}에 신고 전달 실패: {}", nodes.get(i).url(), e.getMessage());
            }
        }
        log.warn("노드 {} 신고를 받아 준 노드가 없다", suspectId);
    }

    private static String idOf(Node node) {
        if (node.id() != null) {
            return node.id();
        }
        try {
            NodeStatusDto s = node.http().get(NodeAdminApi.STATUS, NodeStatusDto.class);
            return s == null ? null : s.id();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
