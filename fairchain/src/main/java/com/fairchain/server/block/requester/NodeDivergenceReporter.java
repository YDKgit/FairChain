package com.fairchain.server.block.requester;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.NodeReportApi;
import com.fairchain.nodeapi.dto.DivergenceReportDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import com.fairchain.nodeapi.dto.ReportAckDto;
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
 * 판정 · 제외는 노드(리더 층 리더)가 한다 — FairChain 혼자서는 기관(노드)을 뺄 수 없다.
 *
 * 신고는 따로 도는 스레드에서 보내 조회 응답을 늦추지 않는다. 같은 노드는 10초에 한 번만 신고한다.
 * 노드 ID는 설정에 적지 않고 노드의 상태 조회에서 받는다 (FairChain 설정은 지금처럼 주소 목록만).
 */
@Component
public class NodeDivergenceReporter {

    private static final Logger log = LoggerFactory.getLogger(NodeDivergenceReporter.class);
    private static final long MIN_INTERVAL_MILLIS = 10_000;

    private final List<HttpRequester> nodes;
    private final Map<Integer, String> ids = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastReport = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "node-report");
        t.setDaemon(true);
        return t;
    });

    public NodeDivergenceReporter(List<HttpRequester> nodeRequesters) {
        this.nodes = nodeRequesters;
    }

    @PreDestroy
    public void stop() {
        sender.shutdownNow();
    }

    /**
     * @param nodeIndex  다수와 다른 답을 한 노드 (fairchain.nodes.urls 순서)
     * @param blockIndex 다르게 보인 블록 번호 (모르면 null)
     * @param recordKey  다르게 보인 좌석 키 (모르면 null)
     */
    public void report(int nodeIndex, Long blockIndex, String recordKey) {
        long now = System.currentTimeMillis();
        Long last = lastReport.get(nodeIndex);
        if (last != null && now - last < MIN_INTERVAL_MILLIS) {
            return;
        }
        lastReport.put(nodeIndex, now);
        try {
            sender.execute(() -> send(nodeIndex, blockIndex, recordKey));
        } catch (RuntimeException ignored) {
            // 끄는 중
        }
    }

    private void send(int nodeIndex, Long blockIndex, String recordKey) {
        String suspect = idOf(nodeIndex);
        if (suspect == null) {
            log.warn("노드 {}의 ID를 몰라 신고하지 못함", nodeIndex + 1);
            return;
        }
        DivergenceReportDto report = new DivergenceReportDto(suspect, blockIndex, recordKey, "fairchain");
        for (int i = 0; i < nodes.size(); i++) {
            if (i == nodeIndex) {
                continue;
            }
            try {
                ReportAckDto ack = nodes.get(i).post(NodeReportApi.REPORTS, report, ReportAckDto.class);
                if (ack != null && ack.accepted()) {
                    log.info("노드 {} 신고 → 판정 {}{}", suspect, ack.reason(),
                            ack.verdict() == null ? "" : " (블록 #" + ack.verdict().blockIndex() + " " + ack.verdict().action() + ")");
                    return;
                }
            } catch (RuntimeException e) {
                log.debug("노드 {}에 신고 전달 실패: {}", i + 1, e.getMessage());
            }
        }
        log.warn("노드 {} 신고를 받아 준 노드가 없다", suspect);
    }

    private String idOf(int nodeIndex) {
        return ids.computeIfAbsent(nodeIndex, i -> {
            try {
                NodeStatusDto s = nodes.get(i).get(NodeAdminApi.STATUS, NodeStatusDto.class);
                return s == null ? null : s.id();
            } catch (RuntimeException e) {
                return null;
            }
        });
    }
}
