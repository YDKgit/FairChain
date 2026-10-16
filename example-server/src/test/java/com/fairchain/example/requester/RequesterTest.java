package com.fairchain.example.requester;

import com.fairchain.api.AdminApi;
import com.fairchain.api.CheckinApi;
import com.fairchain.api.QueueApi;
import com.fairchain.api.ReservationApi;
import com.fairchain.api.TicketVerificationApi;
import com.fairchain.api.dto.AnchorListRequestDto;
import com.fairchain.api.dto.EnterQueueRequestDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.SeatIssueRequestDto;
import com.fairchain.api.dto.VerifyRequestDto;
import com.fairchain.api.http.HttpRequester;
import com.fairchain.api.util.Sha256RecordHasher;
import com.fairchain.example.common.FairChainException;
import com.fairchain.example.config.FairChainProperties;
import com.fairchain.example.entity.Seat;
import com.fairchain.example.entity.Ticket;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequesterTest {

    private final FakeHttpRequester http = new FakeHttpRequester();
    private final RecordFactory factory = new RecordFactory(new Sha256RecordHasher(),
            new FairChainProperties("http://localhost:8081", "key", "client-1", Duration.ofSeconds(3)));

    private final Ticket ticket = new Ticket(3L, 7L, 1L, "qr", 2L, LocalDateTime.of(2026, 10, 16, 12, 0));

    @Test
    void 대기열_진입은_ENTER_경로로_userId_eventId_전송() {
        new QueueRequester(http).enter(3L, 1L);
        assertEquals("POST", http.method);
        assertEquals(QueueApi.ENTER, http.path);
        assertEquals(new EnterQueueRequestDto(3L, 1L), http.body);
    }

    @Test
    void 대기열_이탈과_구독은_쿼리_파라미터로_전송() {
        QueueRequester requester = new QueueRequester(http);
        requester.leave(3L, 1L);
        assertEquals("DELETE", http.method);
        assertEquals(QueueApi.LEAVE + "?userId=3&eventId=1", http.path);

        requester.leaveAll(3L);
        assertEquals(QueueApi.LEAVE_ALL + "?userId=3", http.path);

        requester.subscribe(3L, 1L, event -> {});
        assertEquals("SUBSCRIBE", http.method);
        assertEquals(QueueApi.SUBSCRIBE + "?userId=3&eventId=1", http.path);
    }

    @Test
    void 기록_등록은_RECORDS_경로로_RecordFactory_결과_전송() {
        new ReservationRequester(http, factory).registerRecord(ticket, RecordType.PURCHASE);
        assertEquals(ReservationApi.RECORDS, http.path);
        assertEquals(factory.build(ticket, RecordType.PURCHASE), http.body);
    }

    @Test
    void 재배정_명단은_경로_헬퍼로_조회() {
        new ReservationRequester(http, factory).getReallocationCandidates(1L);
        assertEquals("GET", http.method);
        assertEquals(ReservationApi.reallocationCandidatesPath(1L), http.path);
    }

    @Test
    void 체크인_검증은_지문만_전송() {
        new CheckinRequester(http, factory).verify(ticket);
        assertEquals(CheckinApi.VERIFY, http.path);
        assertEquals(new VerifyRequestDto(factory.recordKey(7L), 2L, factory.recordHash(ticket)), http.body);
    }

    @Test
    void 입장_기록은_RECORDS_경로로_CHECKIN() {
        new CheckinRequester(http, factory).recordCheckin(ticket);
        assertEquals(ReservationApi.RECORDS, http.path);
        assertEquals(RecordType.CHECKIN, ((RecordRequestDto) http.body).type());
    }

    @Test
    void 좌석_등록은_좌석마다_ISSUE_기록() {
        Seat first = new Seat(10L, "A-1");
        Seat second = new Seat(10L, "A-2");
        ReflectionTestUtils.setField(first, "id", 7L);
        ReflectionTestUtils.setField(second, "id", 8L);

        new AdminRequester(http, factory).issueSeats(1L, List.of(first, second));

        assertEquals(AdminApi.ISSUE_SEATS, http.path);
        SeatIssueRequestDto body = (SeatIssueRequestDto) http.body;
        assertEquals(1L, body.eventId());
        assertEquals(List.of(factory.buildIssue(1L, first), factory.buildIssue(1L, second)), body.records());
    }

    @Test
    void 키_조회와_티켓_기록_조회_경로() {
        new AdminRequester(http, factory).getKeys();
        assertEquals(AdminApi.KEYS, http.path);

        new TicketVerificationRequester(http).getVerifiedAnchors(new AnchorListRequestDto(3L, 1L));
        assertEquals(TicketVerificationApi.ANCHORS, http.path);
        assertEquals(new AnchorListRequestDto(3L, 1L), http.body);
    }

    @Test
    void FairChain_429는_429로_감쌈() {
        http.toThrow = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                HttpHeaders.EMPTY, null, null);
        FairChainException e = assertThrows(FairChainException.class, () -> new QueueRequester(http).enter(3L, 1L));
        assertEquals(429, e.getHttpStatus());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.responseStatus());
    }

    @Test
    void FairChain_5xx는_502로_감쌈() {
        http.toThrow = HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                HttpHeaders.EMPTY, null, null);
        FairChainException e = assertThrows(FairChainException.class,
                () -> new ReservationRequester(http, factory).registerRecord(ticket, RecordType.PURCHASE));
        assertEquals(500, e.getHttpStatus());
        assertEquals(HttpStatus.BAD_GATEWAY, e.responseStatus());
    }

    @Test
    void 타임아웃은_502로_감쌈() {
        http.toThrow = new ResourceAccessException("Read timed out");
        FairChainException e = assertThrows(FairChainException.class, () -> new AdminRequester(http, factory).getKeys());
        assertEquals(0, e.getHttpStatus());
        assertEquals(HttpStatus.BAD_GATEWAY, e.responseStatus());
        assertTrue(e.getMessage().contains("API 키 조회"));
    }

    /** 마지막 호출만 기억하는 가짜 HttpRequester */
    static class FakeHttpRequester implements HttpRequester {

        String method;
        String path;
        Object body;
        RuntimeException toThrow;

        @Override
        public <T> T post(String path, Object body, Class<T> responseType) {
            record("POST", path, body);
            return null;
        }

        @Override
        public <T> T get(String path, Class<T> responseType) {
            record("GET", path, null);
            return null;
        }

        @Override
        public void delete(String path) {
            record("DELETE", path, null);
        }

        @Override
        public <T> void subscribe(String path, Class<T> eventType, Consumer<T> onEvent) {
            record("SUBSCRIBE", path, null);
        }

        private void record(String method, String path, Object body) {
            this.method = method;
            this.path = path;
            this.body = body;
            if (toThrow != null) {
                throw toThrow;
            }
        }
    }
}
