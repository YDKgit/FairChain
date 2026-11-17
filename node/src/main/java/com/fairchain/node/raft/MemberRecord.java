package com.fairchain.node.raft;

/**
 * 멤버 명단의 노드 한 줄 (미정사항 1 · 9번).
 *
 * @param publicKey Ed25519 공개키 (X.509, Base64). 아직 등록 전이면 null
 */
public record MemberRecord(String id, String team, String url, String publicKey) {}
