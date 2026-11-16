package com.fairchain.node.raft;

/** 확정 블록이 이 노드 H2에 들어갔다 (Raft 적용 또는 따라잡기). 팀 리더가 팀원에게 보낼 때가 됐다는 신호 */
public record BlockStoredEvent(long index) {}
