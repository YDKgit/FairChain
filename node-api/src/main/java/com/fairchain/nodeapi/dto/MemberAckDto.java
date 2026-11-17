package com.fairchain.nodeapi.dto;

/**
 * 팀원 → 팀 리더: 블록을 받아 검증 · 저장한 결과 (미정사항 2번).
 *
 * @param stored         모두 저장했으면 true
 * @param reason         저장하지 못한 이유 (stored면 OK)
 * @param lastBlockIndex 팀원의 마지막 블록 번호
 * @param lastBlockHash  팀원의 마지막 블록 해시
 */
public record MemberAckDto(boolean stored, ReasonCode reason, Long lastBlockIndex, String lastBlockHash) {}
