package com.fairchain.nodeapi.dto;

/**
 * 팀원 → 팀 리더: "나 여기 있다" (1초마다). 팀원은 Raft 멤버가 아니라서 팀 리더에 등록만 하면 된다 (11/12 답변 5장).
 * 팀 리더는 이것으로 블록을 보낼 주소와 팀원이 어디까지 갖고 있는지 안다.
 *
 * @param url            팀원 주소 (팀 리더가 블록을 보낼 곳)
 * @param lastBlockIndex 팀원의 마지막 블록 번호
 * @param lastBlockHash  팀원의 마지막 블록 해시
 * @param state          팀원 스스로 본 상태: OK 또는 CHAIN_INVALID (켤 때 검사에서 자기 체인이 깨진 것을 발견)
 */
public record TeamHelloDto(String id, String team, String url, Long lastBlockIndex, String lastBlockHash,
                           ReasonCode state) {}
