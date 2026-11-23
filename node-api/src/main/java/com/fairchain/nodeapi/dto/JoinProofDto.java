package com.fairchain.nodeapi.dto;

/**
 * 가입 전 확인 응답 — 새 노드가 자기 정보와 두 가지 증명을 돌려준다.
 *
 * 증명 대상은 {@code nonce · id · team · role · url · publicKey}를 "길이:값"으로 이은 바이트다.
 *
 * @param id        노드 ID
 * @param team      팀(기관) ID
 * @param role      leader-layer 또는 member (리더 층에는 leader-layer만 가입한다)
 * @param url       노드가 알고 있는 자기 주소 (node.url). 리더는 운영자가 입력한 주소와 같은지 본다 —
 *                  가짜 주소가 진짜 노드의 증명을 받아다 그대로 전달하는 것을 막는다
 * @param publicKey Ed25519 공개키 (X.509, Base64). 멤버 명단에 등록될 값
 * @param mac       HMAC-SHA256(노드 공유 비밀값, 증명 대상), Base64 — 비밀값을 안다는 증명
 * @param signature Ed25519 서명(증명 대상), Base64 — 그 공개키의 개인키를 갖고 있다는 증명
 */
public record JoinProofDto(String id, String team, String role, String url, String publicKey, String mac,
                           String signature) {}
