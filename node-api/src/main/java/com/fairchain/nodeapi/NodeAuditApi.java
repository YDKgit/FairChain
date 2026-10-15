package com.fairchain.nodeapi;

import com.fairchain.nodeapi.dto.BlockProofDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** FairChain → 노드: 무결성 점검 (PENDING 복구 · 클러스터 감사, SS-06) — NodeController가 구현 */
public interface NodeAuditApi {

    String PROOF = "/node/records/proof/{recordKey}";
    String LEDGER_STATUS = "/node/ledger/status";

    /** 그 좌석 키의 확정된 최신 기록 증명 (블록 번호 · 버전 · 지문 · 블록 해시). 체인이 깨졌으면 valid=false */
    @GetMapping(PROOF)
    BlockProofDto proof(@PathVariable("recordKey") String recordKey);

    /** 원장 상태: 높이 · 마지막 블록 해시 · 체인 무결성 · 미확정 제안 */
    @GetMapping(LEDGER_STATUS)
    NodeLedgerStatusDto ledgerStatus();

    static String proofPath(String recordKey) {
        return PROOF.replace("{recordKey}", recordKey);
    }
}
