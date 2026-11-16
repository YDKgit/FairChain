package com.fairchain.node.raft;

import com.fairchain.nodeapi.dto.VerdictDto;

import java.util.List;
import java.util.Map;

/**
 * Raft 스냅샷 (로그를 자를 때 남기는 상태, 계획 4.6).
 *
 * 블록 자체는 넣지 않는다 — 블록 체인이 스냅샷 역할을 하고(Fabric 방식, 미정사항 16번), 빠진 블록은
 * {@code GET /node/consensus/blocks?from=}로 받아 마지막 번호 · 해시와 맞는지 확인한다.
 * 대신 로그를 자르면 사라지는 것(멤버 명단 · 공개키 · 제외 · 판정 · 기록 중단)은 여기에 남긴다.
 *
 * @param excluded 제외된 노드 ID → 제외가 기록된 Raft 로그 번호
 * @param restored 노드 ID → 마지막 복귀가 기록된 Raft 로그 번호
 * @param halted   기록 중단 상태 (서명이 둘 다 맞는 서로 다른 블록)
 */
public record LedgerSnapshot(
        long lastBlockIndex,
        String lastBlockHash,
        List<MemberRecord> members,
        Map<String, Long> excluded,
        List<VerdictDto> verdicts,
        Map<String, Long> restored,
        boolean halted
) implements RaftPayload {}
