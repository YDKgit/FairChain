package com.fairchain.nodeapi.dto;

import java.util.List;

/**
 * 불일치 판정 기록 (CometBFT evidence와 같은 모양, 미정사항 14번). Raft 로그에 기록된 것을 그대로 보여 준다.
 *
 * @param suspectId      판정 대상 노드
 * @param reason         BEHIND(뒤처짐, 조치 없음) · DIVERGED(갈라짐) · CHAIN_INVALID(체인 손상) · OK(차이 없음)
 * @param blockIndex     처음 갈라진 블록 번호 #k (없으면 null)
 * @param expected       리더 층에서 확정된 #k
 * @param found          대상 노드가 가진 #k
 * @param differentFields 달라진 필드 이름 (recordHash 등)
 * @param action         EXCLUDED · RESTORED · NONE
 * @param decidedBy      판정한 리더 노드 ID
 * @param decidedAt      판정 시각 (epoch 밀리초)
 */
public record VerdictDto(
        String suspectId,
        ReasonCode reason,
        Long blockIndex,
        BlockProposalDto expected,
        BlockProposalDto found,
        List<String> differentFields,
        String action,
        String decidedBy,
        Long decidedAt
) {}
