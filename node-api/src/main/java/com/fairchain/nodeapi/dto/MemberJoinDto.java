package com.fairchain.nodeapi.dto;

/**
 * 리더 층 가입 요청 (미정사항 1 · 15번). 멤버는 노드이고 팀 ID는 그 노드의 속성이다.
 *
 * @param id        노드 ID (예: "D1")
 * @param team      팀(기관) ID (예: "D")
 * @param url       노드 주소 (예: "http://192.168.0.14:9001")
 * @param publicKey Ed25519 공개키 (X.509, Base64)
 */
public record MemberJoinDto(String id, String team, String url, String publicKey) {}
