package com.fairchain.nodeapi.dto;

/**
 * 리더 층 멤버 한 명.
 *
 * @param voting   투표 멤버면 true, learner(따라잡는 중)면 false
 * @param excluded 제외(투표 · 후보 · 블록 제공 중지) 상태면 true. 제외돼도 멤버 명단에는 남는다 (제외 ≠ 해지)
 */
public record MemberInfoDto(String id, String team, String url, String publicKey, boolean voting, boolean excluded) {}
