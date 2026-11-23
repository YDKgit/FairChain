package com.fairchain.nodeapi.dto;

/**
 * 리더 층 가입 요청 (미정사항 1 · 15번). 멤버는 노드이고 팀 ID는 그 노드의 속성이다.
 *
 * 가입({@code POST /node/admin/members})에는 주소만 있으면 된다 — ID · 팀 · 공개키는 리더가 그 주소의 노드에게
 * 직접 물어 확인한 값을 쓴다 ({@link com.fairchain.nodeapi.NodeJoinApi}). 같이 적으면 노드가 답한 값과 같은지 본다.
 * 공개키 등록({@code POST /node/consensus/keys})에는 ID와 공개키가 필요하다.
 *
 * @param id        노드 ID (예: "D1"). 가입 요청에서는 생략 가능
 * @param team      팀(기관) ID (예: "D"). 가입 요청에서는 생략 가능
 * @param url       노드 주소 (예: "http://192.168.0.14:9001")
 * @param publicKey Ed25519 공개키 (X.509, Base64). 가입 요청에서는 생략 가능
 */
public record MemberJoinDto(String id, String team, String url, String publicKey) {}
