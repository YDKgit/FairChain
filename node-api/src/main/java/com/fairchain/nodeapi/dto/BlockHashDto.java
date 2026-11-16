package com.fairchain.nodeapi.dto;

/**
 * 블록 하나의 해시 — 갈라진 번호 #k를 이진 탐색으로 찾을 때 쓴다.
 *
 * @param blockHash      그 번호의 블록 해시 (블록이 없으면 null)
 * @param lastBlockIndex 응답한 노드의 마지막 블록 번호
 */
public record BlockHashDto(Long blockIndex, String blockHash, Long lastBlockIndex) {}
