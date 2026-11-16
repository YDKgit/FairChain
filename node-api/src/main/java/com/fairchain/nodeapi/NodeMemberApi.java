package com.fairchain.nodeapi;

import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.MemberAckDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 팀 리더 → 팀원: 확정 블록 전달 (미정사항 2번) — 팀원 노드(node.role=member)의 NodeController가 구현.
 * 팀원은 번호 · prevBlockHash · blockHash · 서명을 검증한 뒤 저장하고, 결과와 자기 마지막 번호 · 해시를 돌려준다.
 */
public interface NodeMemberApi {

    String BLOCKS = "/node/member/blocks";

    @PostMapping(BLOCKS)
    MemberAckDto receiveBlocks(@RequestBody List<BlockProposalDto> blocks);
}
