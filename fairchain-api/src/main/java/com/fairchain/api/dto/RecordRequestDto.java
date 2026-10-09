package com.fairchain.api.dto;

/**
 * 모든 기록 요청 공용 양식 (ISSUE · PURCHASE · CANCEL · CHECKIN).
 * 노드에는 recordKey · version · recordHash만 전달되고, 나머지는 FairChain DB에만 남는다.
 *
 * @param userId  ISSUE(발행)일 때는 null
 * @param slotKey FairChain이 뜻을 해석하지 않는 고객사 좌석 식별값
 */
public record RecordRequestDto(
        RecordType type,
        Long eventId,
        Long userId,
        String slotKey,
        String recordKey,
        Long version,
        String recordHash
) {}
