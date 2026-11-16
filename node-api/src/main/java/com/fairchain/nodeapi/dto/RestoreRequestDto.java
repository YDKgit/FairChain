package com.fairchain.nodeapi.dto;

/**
 * 복귀 요청 (미정사항 11번): 제외된 노드가 #k부터 지우고 다시 받아 리더 층과 같은 체인이 됐으니 다시 넣어 달라는 요청.
 * 리더는 자기 체인의 height번 블록 해시가 headHash와 같은지 보고 Raft 로그에 복귀(Restore)를 기록한다.
 */
public record RestoreRequestDto(String nodeId, Long height, String headHash) {}
