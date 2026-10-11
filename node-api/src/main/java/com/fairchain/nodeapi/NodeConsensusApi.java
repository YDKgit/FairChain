package com.fairchain.nodeapi;

import com.fairchain.nodeapi.dto.BlockProposalDto;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** 노드 ↔ 노드: 데모 제안/확정 (PBFT 아님) — NodeController가 구현, NodePeerRequester가 호출 */
public interface NodeConsensusApi {

    String PROPOSALS = "/node/consensus/proposals";
    String CONFIRM = "/node/consensus/blocks/{blockIndex}/confirm";

    /** 제안 블록 검증 후 찬성(true) / 반대(false) */
    @PostMapping(PROPOSALS)
    boolean receiveProposal(@RequestBody BlockProposalDto dto);

    /** 준비된 블록 해시를 검증하여 확정 */
    @PostMapping(CONFIRM)
    boolean confirmBlock(@PathVariable("blockIndex") Long blockIndex, @RequestParam("blockHash") String blockHash);

    static String confirmPath(Long blockIndex) {
        return CONFIRM.replace("{blockIndex}", String.valueOf(blockIndex));
    }
}
