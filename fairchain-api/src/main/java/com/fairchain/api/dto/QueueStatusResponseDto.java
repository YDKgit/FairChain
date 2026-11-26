package com.fairchain.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 대기열 상태.
 *
 * @param state        {@link #WAITING} · {@link #ADMITTED} · {@link #EXPIRED} · {@link #NOT_FOUND}
 * @param rank         현재 대기 순위 (WAITING일 때, 1부터)
 * @param waitingCount 전체 대기 인원 (WAITING일 때)
 * @param nextPollMs   WAITING일 때 다음 상태 확인까지 기다릴 시간(ms). 앞쪽일수록 짧고 뒤쪽일수록 길다 — 대기 인원이 많아도
 *                     상태 확인 요청이 서버를 덮지 않게 서버가 정해 준다 (Cloudflare Waiting Room의 새로 고침 간격과 같은 생각).
 *                     WAITING이 아니면 null (더 물을 필요가 없다)
 */
public record QueueStatusResponseDto(String state, Long rank, Long waitingCount, Long nextPollMs) {

    /** 줄에 서 있음 */
    public static final String WAITING = "WAITING";
    /** 입장 허용됨 (입장 권한이 살아 있음) */
    public static final String ADMITTED = "ADMITTED";
    /** 입장했었지만 권한이 만료됨 */
    public static final String EXPIRED = "EXPIRED";
    /** 줄에 선 적이 없음 */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** 다음 확인 간격 없이 (WAITING이 아닐 때 · 예전 형식) */
    public QueueStatusResponseDto(String state, Long rank, Long waitingCount) {
        this(state, rank, waitingCount, null);
    }

    /** 계산한 값이라 JSON에는 싣지 않는다 (실으면 받는 쪽이 모르는 필드로 읽다 실패한다) */
    @JsonIgnore
    public boolean isWaiting() {
        return WAITING.equals(state);
    }
}
