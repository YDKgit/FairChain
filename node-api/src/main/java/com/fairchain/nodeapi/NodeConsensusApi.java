package com.fairchain.nodeapi;

import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 노드 ↔ 노드: 제안(PREPARE) → 확정 결정(DECISION) → 확정(CONFIRM) — NodeController가 구현, 제안한 노드가 호출.
 * 3대 중 2대가 찬성하면 확정 (PBFT 아님). 꺼져 있던 노드는 BLOCKS로 확정 블록을 받아 따라잡는다.
 */
public interface NodeConsensusApi {

    String PROPOSALS = "/node/consensus/proposals";
    String DECISION = "/node/consensus/blocks/{blockIndex}/decision";
    String CONFIRM = "/node/consensus/blocks/{blockIndex}/confirm";
    String RECOVERY = "/node/consensus/recovery/{blockIndex}";
    String BLOCKS = "/node/consensus/blocks";

    /** 제안 블록 검증 후 찬성(true) / 반대(false) */
    @PostMapping(PROPOSALS)
    boolean receiveProposal(@RequestBody BlockProposalDto dto);

    /** 확정 결정을 먼저 저장 (CONFIRM 전에 꺼져도 같은 블록으로 복구할 수 있게) */
    @PostMapping(DECISION)
    boolean acceptDecision(@PathVariable("blockIndex") Long blockIndex, @RequestParam("blockHash") String blockHash);

    /** 준비된 블록 해시를 검증하여 확정 */
    @PostMapping(CONFIRM)
    boolean confirmBlock(@PathVariable("blockIndex") Long blockIndex, @RequestParam("blockHash") String blockHash);

    /** [운영자 수동] 끝나지 못하고 남은 제안을 다른 노드에 다시 돌려 확정 */
    @PostMapping(RECOVERY)
    BlockResultDto retryPrepared(@PathVariable("blockIndex") Long blockIndex, @RequestParam("blockHash") String blockHash);

    /** 확정된 블록을 fromIndex번부터 번호 순으로 조회 — 뒤처진 노드가 따라잡을 때 사용 */
    @GetMapping(BLOCKS)
    List<BlockProposalDto> committedBlocks(@RequestParam("from") Long fromIndex,
                                           @RequestParam(value = "limit", defaultValue = "200") int limit);

    static String decisionPath(Long blockIndex) {
        return DECISION.replace("{blockIndex}", String.valueOf(blockIndex));
    }

    static String confirmPath(Long blockIndex) {
        return CONFIRM.replace("{blockIndex}", String.valueOf(blockIndex));
    }
}
