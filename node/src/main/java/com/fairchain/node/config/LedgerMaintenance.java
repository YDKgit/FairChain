package com.fairchain.node.config;

import com.fairchain.node.service.NodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 주기 점검 — 체인 전체 무결성 검사. 깨진 것이 발견되면 노드가 새 기록을 받지 않는다 */
@Configuration
@EnableScheduling
public class LedgerMaintenance {

    private static final Logger log = LoggerFactory.getLogger(LedgerMaintenance.class);

    private final NodeService nodeService;

    public LedgerMaintenance(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    @Scheduled(initialDelayString = "${node.audit-interval-ms:5000}", fixedDelayString = "${node.audit-interval-ms:5000}")
    public void run() {
        try {
            nodeService.auditChain();
        } catch (RuntimeException e) {
            log.warn("주기 점검 실패: {}", e.getMessage());
        }
    }
}
