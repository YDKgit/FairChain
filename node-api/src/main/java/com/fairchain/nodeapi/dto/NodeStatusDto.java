package com.fairchain.nodeapi.dto;

import java.util.List;

/**
 * 노드 상태 (점검 화면 · 운영자용).
 *
 * @param role        leader-layer(리더 층 멤버) · member(팀원 사본)
 * @param raftRole    LEADER · FOLLOWER · LEARNER · CANDIDATE · NONE(Raft를 돌리지 않음: 팀원 · 제외됨)
 * @param members     리더 층 멤버 명단 (공개키 · 투표 여부 · 제외 여부 포함)
 * @param learners    learner 상태인 멤버 ID
 * @param excluded    제외된 노드 ID (리더 층 멤버와 팀원 모두)
 * @param verdicts    판정 기록 (오래된 것부터): 제외 · 복귀 · 기록 중단 · 재개
 * @param publicKey   이 노드의 공개키 (Ed25519, X.509 Base64) — 가입 요청에 넣을 값
 * @param teamLeader  이 팀의 팀 리더 노드 ID (리더 층 노드면 자기 자신)
 * @param teamMembers 팀 리더가 보는 팀원 상태 (팀원 노드에서는 빈 목록)
 * @param outReason   이 노드가 빠져 있는 이유 (제외 · 체인 손상 · 복구 중). 참여 중이면 null
 * @param halted      서명이 둘 다 맞는 서로 다른 블록이 나와 기록을 멈춘 상태 (관리자 확인 필요)
 */
public record NodeStatusDto(
        String id,
        String team,
        String role,
        String raftRole,
        Integer term,
        String leaderId,
        List<MemberInfoDto> members,
        List<String> learners,
        List<String> excluded,
        List<VerdictDto> verdicts,
        Long height,
        String headHash,
        String publicKey,
        String teamLeader,
        List<TeamMemberDto> teamMembers,
        String outReason,
        boolean halted
) {}
