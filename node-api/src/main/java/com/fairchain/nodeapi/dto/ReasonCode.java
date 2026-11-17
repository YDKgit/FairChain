package com.fairchain.nodeapi.dto;

/**
 * 거부 · 판정 이유 코드 (변경안 11/06 3-4장). 예전의 true/false 응답을 대신한다.
 *
 * <ul>
 *   <li>{@link #OK} — 정상 처리</li>
 *   <li>{@link #STALE_TERM} — 더 새로운 리더가 있음 → 팔로워로 내려감</li>
 *   <li>{@link #BEHIND} — 받는 쪽이 뒤처짐 → 빠진 블록을 받아 따라잡음 (신고하지 않음)</li>
 *   <li>{@link #DIVERGED} — 기록이 갈라짐 → 판정 절차 (이진 탐색 · 서명 검증)</li>
 *   <li>{@link #INVALID_BLOCK} — 블록 내용 · 서명이 틀림 (같은 좌석 키의 버전이 오르지 않는 기록 포함)</li>
 *   <li>{@link #CHAIN_INVALID} — 받는 쪽 체인이 망가짐 → 복구될 때까지 제외</li>
 *   <li>{@link #ALREADY_VOTED} — 그 번호에 이미 다른 블록이 있음</li>
 *   <li>{@link #NOT_LEADER} — 리더를 모르거나 리더에게 전달하지 못함 → 다른 노드로 (Raft 표준 redirect,
 *       MicroRaft {@code NotLeaderException})</li>
 *   <li>{@link #TIMEOUT} — 정해진 시간 안에 과반이 저장하지 않음. 나중에 확정될 수도 있으므로 같은 기록을 다시 보낸다
 *       (다시 보내면 확정된 경우 같은 블록 번호가 돌아온다, MicroRaft {@code IndeterminateStateException})</li>
 * </ul>
 * NOT_LEADER · TIMEOUT은 노드끼리의 거부 코드가 아니라 FairChain에 돌려주는 기록 결과라서 11/06 목록에 더했다.
 */
public enum ReasonCode {
    OK,
    STALE_TERM,
    BEHIND,
    DIVERGED,
    INVALID_BLOCK,
    CHAIN_INVALID,
    ALREADY_VOTED,
    NOT_LEADER,
    TIMEOUT
}
