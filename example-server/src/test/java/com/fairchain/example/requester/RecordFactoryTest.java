package com.fairchain.example.requester;

import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.util.RecordHasher;
import com.fairchain.api.util.Sha256RecordHasher;
import com.fairchain.example.config.FairChainProperties;
import com.fairchain.example.entity.Seat;
import com.fairchain.example.entity.Ticket;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecordFactoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0);

    private final RecordHasher hasher = new Sha256RecordHasher();
    private final RecordFactory factory = new RecordFactory(hasher,
            new FairChainProperties("http://localhost:8081", "key", "client-1", Duration.ofSeconds(3)));

    /** concertId=1, seatId=7, memberId=3, version=2 */
    private Ticket ticket() {
        return new Ticket(3L, 7L, 1L, "qr", 2L, NOW);
    }

    @Test
    void recordKey는_clientId와_seatId로_계산() {
        assertEquals(hasher.recordKey("client-1", 7L), factory.recordKey(7L));
    }

    @Test
    void 같은_티켓_상태면_같은_지문() {
        assertEquals(factory.recordHash(ticket()), factory.recordHash(ticket()));
    }

    @Test
    void 지문은_공연_좌석_회원_상태_버전_순서로_계산() {
        assertEquals(hasher.hash("1", "7", "3", "ACTIVE", "2"), factory.recordHash(ticket()));
    }

    @Test
    void 취소하면_CANCELLED와_새_버전으로_지문이_바뀜() {
        Ticket ticket = ticket();
        String before = factory.recordHash(ticket);
        ticket.cancel(3L);
        assertNotEquals(before, factory.recordHash(ticket));
        assertEquals(hasher.hash("1", "7", "3", "CANCELLED", "3"), factory.recordHash(ticket));
    }

    @Test
    void 체크인_지문은_CHECKED_IN() {
        Ticket ticket = ticket();
        ticket.checkIn(NOW, 5L);
        assertEquals(hasher.hash("1", "7", "3", "CHECKED_IN", "5"), factory.build(ticket, RecordType.CHECKIN).recordHash());
    }

    @Test
    void ISSUE_지문은_주인없음_ISSUE_버전1() {
        assertEquals(hasher.hash("1", "7", null, "ISSUE", "1"), factory.issueHash(1L, 7L));
        // null은 빈 문자열과 다르게 인코딩된다
        assertNotEquals(hasher.hash("1", "7", "", "ISSUE", "1"), factory.issueHash(1L, 7L));
    }

    @Test
    void build는_기록_요청_필드를_채움() {
        RecordRequestDto dto = factory.build(ticket(), RecordType.PURCHASE);
        assertEquals(RecordType.PURCHASE, dto.type());
        assertEquals(1L, dto.eventId());
        assertEquals(3L, dto.userId());
        assertEquals("7", dto.slotKey());
        assertEquals(factory.recordKey(7L), dto.recordKey());
        assertEquals(2L, dto.version());
        assertEquals(factory.recordHash(ticket()), dto.recordHash());
    }

    @Test
    void buildIssue는_userId_없이_버전1() {
        Seat seat = new Seat(10L, "A-1");
        ReflectionTestUtils.setField(seat, "id", 7L);
        RecordRequestDto dto = factory.buildIssue(1L, seat);
        assertEquals(RecordType.ISSUE, dto.type());
        assertNull(dto.userId());
        assertEquals("7", dto.slotKey());
        assertEquals(1L, dto.version());
        assertEquals(factory.issueHash(1L, 7L), dto.recordHash());
    }
}
