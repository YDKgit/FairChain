package com.fairchain.node.config;

import com.fairchain.node.service.NodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 주기 점검 — 체인 전체 검사, 뒤처진 블록 따라잡기, 끝나지 못한 제안 마무리 */
@Configuration
@EnableScheduling
public class LedgerMaintenance {

    private static final Logger log = LoggerFactory.getLogger(LedgerMaintenance.class);

    private final NodeService nodeService;

    public LedgerMaintenance(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    @Scheduled(initialDelayString = "${node.sync-initial-delay-ms:1000}", fixedDelayString = "${node.sync-interval-ms:5000}")
    public void run() {
        try {
            nodeService.maintain();
        } catch (RuntimeException e) {
            log.warn("주기 점검 실패: {}", e.getMessage());
        }
    }
}
