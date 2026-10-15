package com.fairchain.example.common;

import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.util.RecordHasher;
import org.springframework.stereotype.Component;

/**
 * 좌석 키 · 티켓 지문 계산 (FairChain에는 이 두 값만 간다, 개인정보 없음).
 * 키 = hash(clientId, seatId) — 판매 · 취소 · 재판매 · 입장을 거쳐도 그대로.
 * 지문 = hash(concertId, seatId, memberId, status, version) — SD-401-01과 같은 필드 순서.
 */
@Component
public class TicketFingerprint {

    private final RecordHasher hasher;
    private final String clientId;

    public TicketFingerprint(RecordHasher hasher, ExampleProperties properties) {
        this.hasher = hasher;
        this.clientId = properties.fairchain().clientId();
    }

    public String recordKey(Long seatId) {
        return hasher.recordKey(clientId, seatId);
    }

    public String recordHash(Long concertId, Long seatId, Long memberId, String status, Long version) {
        return hasher.hash(String.valueOf(concertId), String.valueOf(seatId),
                memberId == null ? null : String.valueOf(memberId), status, String.valueOf(version));
    }

    /** 기록 종류마다 그 시점의 좌석 · 티켓 상태로 지문을 만든다 */
    public RecordRequestDto record(RecordType type, Long concertId, Long seatId, Long memberId, Long version) {
        return new RecordRequestDto(type, concertId, type == RecordType.ISSUE ? null : memberId,
                String.valueOf(seatId), recordKey(seatId), version,
                recordHash(concertId, seatId, type == RecordType.ISSUE ? null : memberId, statusOf(type), version));
    }

    public static String statusOf(RecordType type) {
        return switch (type) {
            case ISSUE -> "EMPTY";
            case PURCHASE -> "ACTIVE";
            case CANCEL -> "CANCELLED";
            case CHECKIN -> "CHECKED_IN";
        };
    }
}
