package com.fairchain.node.raft;

import com.fairchain.nodeapi.dto.VerdictDto;

/**
 * 이 노드가 빠졌다 (체인 손상 · 제외 판정).
 *
 * @param verdict 제외 판정 (갈라진 번호 #k 포함). 스스로 체인 손상을 발견해 빠졌으면 null — 리더에게 신고해 판정을 받는다
 */
public record NodeLeftEvent(String reason, VerdictDto verdict) {}
