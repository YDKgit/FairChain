package com.fairchain.example.requester;

import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.util.RecordHasher;
import com.fairchain.example.config.FairChainProperties;
import com.fairchain.example.entity.Seat;
import com.fairchain.example.entity.Ticket;
import org.springframework.stereotype.Component;

/**
 * 지문(recordKey · recordHash) 계산 규칙 — 이 클래스 한 곳에만 둔다.
 * 보낼 때(Requester)와 다시 계산할 때(TicketVerificationService)가 같은 값을 내야 검증이 통과한다.
 * <pre>
 * recordKey  = recordKey(clientId, seatId)
 * recordHash = hash(concertId, seatId, memberId, status, version)   ← 필드 순서 고정
 * </pre>
 * status 인자는 ISSUE만 RecordType 이름("ISSUE"), 나머지는 티켓의 현재 TicketStatus 이름.
 */
@Component
public class RecordFactory {

    /** 좌석 등록 시 버전 */
    public static final long ISSUE_VERSION = 1L;

    private final RecordHasher recordHasher;
    private final String clientId;

    public RecordFactory(RecordHasher recordHasher, FairChainProperties properties) {
        this.recordHasher = recordHasher;
        this.clientId = properties.clientId();
    }

    public String recordKey(Long seatId) {
        return recordHasher.recordKey(clientId, seatId);
    }

    /** 티켓의 현재 concertId · seatId · memberId · status · version으로 계산 */
    public String recordHash(Ticket ticket) {
        return hash(ticket.getConcertId(), ticket.getSeatId(), ticket.getMemberId(),
                ticket.getStatus().name(), ticket.getVersion());
    }

    /** ISSUE — 주인 없음(null), status "ISSUE", version 1 */
    public String issueHash(Long concertId, Long seatId) {
        return hash(concertId, seatId, null, RecordType.ISSUE.name(), ISSUE_VERSION);
    }

    public RecordRequestDto build(Ticket ticket, RecordType type) {
        return new RecordRequestDto(type, ticket.getConcertId(), ticket.getMemberId(),
                slotKey(ticket.getSeatId()), recordKey(ticket.getSeatId()), ticket.getVersion(), recordHash(ticket));
    }

    public RecordRequestDto buildIssue(Long concertId, Seat seat) {
        return new RecordRequestDto(RecordType.ISSUE, concertId, null,
                slotKey(seat.getId()), recordKey(seat.getId()), ISSUE_VERSION, issueHash(concertId, seat.getId()));
    }

    private String slotKey(Long seatId) {
        return String.valueOf(seatId);
    }

    private String hash(Long concertId, Long seatId, Long memberId, String status, Long version) {
        return recordHasher.hash(text(concertId), text(seatId), text(memberId), status, text(version));
    }

    /** Long → 문자열, null은 null 그대로 (hasher가 빈 문자열과 구분해 인코딩) */
    private static String text(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
