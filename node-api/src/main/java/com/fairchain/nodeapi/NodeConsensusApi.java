package com.fairchain.nodeapi;

import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 노드 ↔ 노드: 제안(PREPARE) → 확정 결정(DECISION) → 확정(CONFIRM) — NodeController가 구현, 제안한 노드가 호출.
 * 현재 구현은 3대 모두 찬성해야 확정 (PBFT 아님).
 */
public interface NodeConsensusApi {

    String PROPOSALS = "/node/consensus/proposals";
    String DECISION = "/node/consensus/blocks/{blockIndex}/decision";
    String CONFIRM = "/node/consensus/blocks/{blockIndex}/confirm";
    String RECOVERY = "/node/consensus/recovery/{blockIndex}";

    /** 제안 블록 검증 후 찬성(true) / 반대(false) */
    @PostMapping(PROPOSALS)
    boolean receiveProposal(@RequestBody BlockProposalDto dto);

    /** 확정 결정을 먼저 저장 (CONFIRM 전에 꺼져도 같은 블록으로 복구할 수 있게) */
    @PostMapping(DECISION)
    boolean acceptDecision(@PathVariable("blockIndex") Long blockIndex, @RequestParam("blockHash") String blockHash);

    /** 준비된 블록 해시를 검증하여 확정 */
    @PostMapping(CONFIRM)
    boolean confirmBlock(@PathVariable("blockIndex") Long blockIndex, @RequestParam("blockHash") String blockHash);

    /** [운영자 수동] 확정 결정이 저장된 블록을 다른 노드에 다시 전달 */
    @PostMapping(RECOVERY)
    BlockResultDto retryPrepared(@PathVariable("blockIndex") Long blockIndex, @RequestParam("blockHash") String blockHash);

    static String decisionPath(Long blockIndex) {
        return DECISION.replace("{blockIndex}", String.valueOf(blockIndex));
    }

    static String confirmPath(Long blockIndex) {
        return CONFIRM.replace("{blockIndex}", String.valueOf(blockIndex));
    }
}
