package com.fairchain.nodeapi.dto;

/**
 * 가입 · 해지 요청 결과.
 *
 * @param accepted 멤버 변경이 Raft 로그에 확정됐으면 true
 * @param message  결과 설명 (거부 이유: 팀 3개 미만, 다른 변경 진행 중 등)
 */
public record MembershipResultDto(boolean accepted, String message) {}
