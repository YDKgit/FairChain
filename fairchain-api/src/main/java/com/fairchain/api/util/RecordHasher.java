package com.fairchain.api.util;

/**
 * 좌석 키 · 지문 계산. 예제서버와 FairChain이 같은 구현을 써야 같은 값이 나온다.
 * 필드 순서가 바뀌면 지문도 바뀌므로, 호출하는 쪽은 순서를 고정해서 넘긴다.
 */
public interface RecordHasher {

    /** 좌석 기준 키 = hash(clientId, seatId) — 판매 · 취소 · 재판매 · 입장을 거쳐도 그대로 */
    String recordKey(String clientId, Long seatId);

    String hash(String... fields);
}
