package com.fairchain.node.raft;

import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.VerdictDto;

/**
 * 리더 층 Raft 로그에 기록하는 동작. 확정된 순서대로 모든 리더 층 노드의 {@link BlockStateMachine}이 똑같이 실행한다.
 * 그래서 시각 · 판정자처럼 노드마다 달라질 값은 리더가 만들어 동작 안에 넣는다.
 */
public sealed interface LedgerOp extends RaftPayload {

    /** 블록 하나 추가 (기록 1건 = 블록 1개). 블록은 Raft 리더가 만든다 (Fabric 방식, 미정사항 3번) */
    record AppendBlock(BlockProposalDto block) implements LedgerOp {}

    /**
     * 새 리더가 자기 임기 첫 항목으로 넣는 빈 항목 (Raft §8, MicroRaft {@code getNewTermOperation}).
     * 이 항목이 확정되기 전에는 새 리더가 블록을 만들지 않는다.
     */
    record NewTerm() implements LedgerOp {}

    /** 멤버 명단에 노드 등록 · 갱신 (주소 · 팀 · 공개키). 공개키 명단은 가입 · 해지와 함께 Raft 로그에 남긴다 (미정사항 9번) */
    record RegisterMember(String id, String team, String url, String publicKey) implements LedgerOp {}

    /** 해지: 명단에서 뺀다 (Raft 멤버 변경과 함께, 6단계) */
    record RemoveMember(String id) implements LedgerOp {}

    /**
     * 제외 (미정사항 10 · 14번): 판정 결과와 증거(#k의 두 버전 · 달라진 필드 · 시각, CometBFT evidence와 같은 모양).
     * 제외된 노드는 투표 · 후보 · 블록 제공을 멈춘다. 멤버 명단에는 남는다 (제외 ≠ 해지)
     */
    record Exclude(String nodeId, VerdictDto verdict) implements LedgerOp {}

    /** 복귀: #k부터 다시 받아 리더 층과 같은 체인이 된 노드를 다시 참여시킨다 (미정사항 11번) */
    record Restore(String nodeId, Long height, String headHash, String decidedBy, Long decidedAt) implements LedgerOp {}

    /** 조치 없는 판정 기록 */
    record Verdict(VerdictDto verdict) implements LedgerOp {}

    /** 기록 중단: 서명이 둘 다 맞는데 내용이 다른 블록 — 키 유출 의심 (11/06 변경안 6장) */
    record Halt(VerdictDto verdict) implements LedgerOp {}

    /** 관리자가 확인한 뒤 기록 재개 */
    record Resume(String decidedBy, Long decidedAt) implements LedgerOp {}
}
