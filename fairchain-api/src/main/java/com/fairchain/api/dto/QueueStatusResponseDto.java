package com.fairchain.api.dto;

/**
 * 대기열 상태.
 *
 * @param state        {@link #WAITING} · {@link #ADMITTED} · {@link #EXPIRED} · {@link #NOT_FOUND}
 * @param rank         현재 대기 순위 (WAITING일 때, 1부터)
 * @param waitingCount 전체 대기 인원 (WAITING일 때)
 */
public record QueueStatusResponseDto(String state, Long rank, Long waitingCount) {

    /** 줄에 서 있음 */
    public static final String WAITING = "WAITING";
    /** 입장 허용됨 (입장 권한이 살아 있음) */
    public static final String ADMITTED = "ADMITTED";
    /** 입장했었지만 권한이 만료됨 */
    public static final String EXPIRED = "EXPIRED";
    /** 줄에 선 적이 없음 */
    public static final String NOT_FOUND = "NOT_FOUND";

    public boolean isWaiting() {
        return WAITING.equals(state);
    }
}
