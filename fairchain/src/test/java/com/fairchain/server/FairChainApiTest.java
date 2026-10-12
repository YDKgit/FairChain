package com.fairchain.server;

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
import com.fairchain.api.http.FairChainHeaders;
import com.fairchain.api.util.RecordHasher;
import com.fairchain.api.util.Sha256RecordHasher;
import com.fairchain.nodeapi.dto.BlockBatchVerifyResponseDto;
import com.fairchain.nodeapi.dto.BlockVerifyItemResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import com.fairchain.server.admin.entity.Clients;
import com.fairchain.server.admin.repository.ClientsRepository;
import com.fairchain.server.block.repository.AnchorRecordRepository;
import com.fairchain.server.block.repository.BlockQueueRepository;
import com.fairchain.server.block.requester.BlockRecordAccess;
import com.fairchain.server.block.requester.NodeVerificationAccess;
import com.fairchain.server.block.service.BlockRecordService;
import com.fairchain.server.common.ErrorCode;
import com.fairchain.server.common.FairChainException;
import com.fairchain.server.queue.repository.AdmissionPassRepository;
import com.fairchain.server.queue.repository.IdempotencyKeyCache;
import com.fairchain.server.queue.repository.MacroBlockCounter;
import com.fairchain.server.queue.repository.QueueRepository;
import com.fairchain.server.reservation.entity.Admission;
import com.fairchain.server.reservation.repository.AdmissionRepository;
import com.fairchain.server.reservation.repository.EventRepository;
import com.fairchain.server.reservation.repository.ReallocationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Redis · 노드는 가짜(MockBean), DB는 H2로 FairChain API 흐름 확인 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FairChainApiTest {

    private static final String KEY = "test-key";
    private static final String CLIENT = "example-server";
    private static final long EVENT = 100L;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EventRepository eventRepository;
    @Autowired AdmissionRepository admissionRepository;
    @Autowired AnchorRecordRepository anchorRecordRepository;
    @Autowired ClientsRepository clientsRepository;
    @Autowired BlockRecordService blockRecordService;

    @MockBean QueueRepository queueRepository;
    @MockBean AdmissionPassRepository admissionPassRepository;
    @MockBean MacroBlockCounter macroBlockCounter;
    @MockBean IdempotencyKeyCache idempotencyKeyCache;
    @MockBean ReallocationRepository reallocationRepository;
    @MockBean BlockQueueRepository blockQueueRepository;
    @MockBean BlockRecordAccess blockRecordAccess;
    @MockBean NodeVerificationAccess nodeVerificationAccess;

    private final RecordHasher hasher = new Sha256RecordHasher();

    @BeforeEach
    void setUp() {
        when(macroBlockCounter.increment(anyLong(), anyLong())).thenReturn(1L);
    }

    // ── 공통 ──────────────────────────────────────────

    @Test
    void apiKey가_없거나_틀리면_401() throws Exception {
        mvc.perform(post(ReservationApi.RECORDS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ReservationApi.RECORDS).header(FairChainHeaders.API_KEY, "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 초당_요청_한도를_넘으면_429() throws Exception {
        issueSeats(1);
        when(macroBlockCounter.increment(eq(7L), anyLong())).thenReturn(6L);

        call(QueueApi.ENTER, new EnterQueueRequestDto(7L, EVENT)).andExpect(status().isTooManyRequests());
        verify(macroBlockCounter).incrementBlocked(EVENT);
    }

    @Test
    void 같은_Idempotency_Key면_캐시된_응답() throws Exception {
        issueSeats(1);
        when(idempotencyKeyCache.find(CLIENT + ":k1")).thenReturn("{\"userId\":7,\"sequenceNumber\":3}");

        mvc.perform(post(QueueApi.ENTER).header(FairChainHeaders.API_KEY, KEY).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnterQueueRequestDto(7L, EVENT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequenceNumber").value(3));
        verify(queueRepository, never()).nextSequence(any());
    }

    @Test
    void Swagger_문서에_모든_API가_나온다() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + ReservationApi.RECORDS + "']").exists())
                .andExpect(jsonPath("$.paths['" + QueueApi.ENTER + "']").exists())
                .andExpect(jsonPath("$.paths['" + CheckinApi.VERIFY + "']").exists())
                .andExpect(jsonPath("$.paths['" + TicketVerificationApi.ANCHORS + "']").exists())
                .andExpect(jsonPath("$.paths['" + AdminApi.ISSUE_SEATS + "']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.name").value(FairChainHeaders.API_KEY));
    }

    // ── SS-02 대기열 ─────────────────────────────────────

    @Test
    void 대기열_진입_새_순번_발급() throws Exception {
        issueSeats(1);
        when(queueRepository.zscore(EVENT, 7L)).thenReturn(null);   // Mockito 기본값 0L 대신 "대기 중 아님"
        when(queueRepository.nextSequence(EVENT)).thenReturn(5L);

        call(QueueApi.ENTER, new EnterQueueRequestDto(7L, EVENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequenceNumber").value(5));
        verify(queueRepository).zadd(EVENT, 7L, 5L);
    }

    @Test
    void 이미_대기_중이면_기존_순번() throws Exception {
        issueSeats(1);
        when(queueRepository.zscore(EVENT, 7L)).thenReturn(2L);

        call(QueueApi.ENTER, new EnterQueueRequestDto(7L, EVENT)).andExpect(jsonPath("$.sequenceNumber").value(2));
        verify(queueRepository, never()).nextSequence(any());
    }

    @Test
    void 다른_고객사_공연은_403() throws Exception {
        issueSeats(1);
        clientsRepository.save(new Clients("other", "other-key"));

        mvc.perform(post(QueueApi.ENTER).header(FairChainHeaders.API_KEY, "other-key")
                        .contentType(MediaType.APPLICATION_JSON).content(json(new EnterQueueRequestDto(7L, EVENT))))
                .andExpect(status().isForbidden());
    }

    // ── SS-06 좌석 발행 · SS-03 기록 ─────────────────────────

    @Test
    void 좌석_발행하면_총좌석_남은좌석_증가_사본_PENDING() throws Exception {
        issueSeats(3);

        var event = eventRepository.findById(EVENT).orElseThrow();
        assertThat(event.getTotalSeats()).isEqualTo(3);
        assertThat(event.getRemainingSeats()).isEqualTo(3);
        assertThat(anchorRecordRepository.findAll()).hasSize(3)
                .allSatisfy(r -> assertThat(r.isConfirmed()).isFalse());
        verify(blockQueueRepository, org.mockito.Mockito.times(3)).enqueue(any());
    }

    @Test
    void 마지막_좌석_판매시_매진_및_재배정_명단_생성() throws Exception {
        issueSeats(2);
        admissionRepository.save(Admission.admit(1L, EVENT, 1L));
        admissionRepository.save(Admission.admit(2L, EVENT, 2L));
        admissionRepository.save(Admission.admit(3L, EVENT, 3L));

        call(ReservationApi.RECORDS, record(RecordType.PURCHASE, 1, 1L, 2)).andExpect(jsonPath("$.soldOut").value(false));
        call(ReservationApi.RECORDS, record(RecordType.PURCHASE, 2, 3L, 2)).andExpect(jsonPath("$.soldOut").value(true));

        // 1번 · 3번은 샀고, 2번만 "입장했지만 못 산 사람"
        verify(reallocationRepository).saveCandidates(EVENT, List.of(2L));
        assertThat(eventRepository.findById(EVENT).orElseThrow().getSoldOutAt()).isNotNull();
    }

    @Test
    void 매진_전_취소는_soldOut_false_매진_후_취소는_true() throws Exception {
        issueSeats(1);
        call(ReservationApi.RECORDS, record(RecordType.PURCHASE, 1, 1L, 2));
        call(ReservationApi.RECORDS, record(RecordType.CANCEL, 1, 1L, 3)).andExpect(jsonPath("$.soldOut").value(true));

        issueSeatsFor(200L, 2);
        call(ReservationApi.RECORDS, recordFor(200L, RecordType.PURCHASE, 1, 1L, 2));
        call(ReservationApi.RECORDS, recordFor(200L, RecordType.CANCEL, 1, 1L, 3)).andExpect(jsonPath("$.soldOut").value(false));
        assertThat(eventRepository.findById(200L).orElseThrow().getRemainingSeats()).isEqualTo(2);
    }

    @Test
    void 같은_기록_재전송은_그대로_다른_지문이나_낮은_버전은_409() throws Exception {
        issueSeats(1);
        RecordRequestDto purchase = record(RecordType.PURCHASE, 1, 1L, 2);
        call(ReservationApi.RECORDS, purchase).andExpect(status().isOk());
        call(ReservationApi.RECORDS, purchase).andExpect(status().isOk());
        assertThat(eventRepository.findById(EVENT).orElseThrow().getRemainingSeats()).isZero();

        RecordRequestDto otherHash = new RecordRequestDto(RecordType.PURCHASE, EVENT, 9L, "seat-1",
                purchase.recordKey(), 2L, hasher.hash("forged"));
        call(ReservationApi.RECORDS, otherHash).andExpect(status().isConflict());

        RecordRequestDto older = new RecordRequestDto(RecordType.CANCEL, EVENT, 1L, "seat-1",
                purchase.recordKey(), 1L, hasher.hash("old"));
        call(ReservationApi.RECORDS, older).andExpect(status().isConflict());
    }

    @Test
    void 좌석_일생_순서를_어기면_409_블록_확정은_기다리지_않음() throws Exception {
        // 첫 기록이 ISSUE v1이 아니면 거절
        call(ReservationApi.RECORDS, record(RecordType.PURCHASE, 9, 1L, 1)).andExpect(status().isConflict());

        // 발행 직후(아직 PENDING)에도 판매 가능 → 버전 건너뛰기 · 발행 직후 입장은 거절
        issueSeats(1);
        call(ReservationApi.RECORDS, record(RecordType.PURCHASE, 1, 1L, 3)).andExpect(status().isConflict());
        call(ReservationApi.RECORDS, record(RecordType.CHECKIN, 1, 1L, 2)).andExpect(status().isConflict());
        call(ReservationApi.RECORDS, record(RecordType.PURCHASE, 1, 1L, 2)).andExpect(status().isOk());

        // 입장한 좌석은 끝 — 다음 기록 없음
        call(ReservationApi.RECORDS, record(RecordType.CHECKIN, 1, 1L, 3)).andExpect(status().isOk());
        call(ReservationApi.RECORDS, record(RecordType.CANCEL, 1, 1L, 4)).andExpect(status().isConflict());
    }

    @Test
    void 지문이_SHA256_16진수가_아니면_400() throws Exception {
        issueSeats(1);
        RecordRequestDto purchase = record(RecordType.PURCHASE, 1, 1L, 2);
        call(ReservationApi.RECORDS, new RecordRequestDto(RecordType.PURCHASE, EVENT, 1L, "seat-1",
                purchase.recordKey(), 2L, "h1")).andExpect(status().isBadRequest());
        call(ReservationApi.RECORDS, new RecordRequestDto(RecordType.PURCHASE, EVENT, 1L, "seat-1",
                "seat-A1", 2L, purchase.recordHash())).andExpect(status().isBadRequest());
    }

    // ── SS-04 체크인 검증 ────────────────────────────────

    @Test
    void 노드_최신_지문과_같으면_VERIFIED() throws Exception {
        RecordRequestDto ticket = sold();
        when(blockRecordAccess.verifyRecord(any())).thenReturn(new BlockVerifyResultDto(true, 2L, ticket.recordHash()));

        call(CheckinApi.VERIFY, verifyOf(ticket)).andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    @Test
    void 지문이_다르면_TAMPERED() throws Exception {
        RecordRequestDto ticket = sold();
        when(blockRecordAccess.verifyRecord(any())).thenReturn(new BlockVerifyResultDto(false, 2L, ticket.recordHash()));

        call(CheckinApi.VERIFY, new VerifyRequestDto(ticket.recordKey(), 2L, hasher.hash("DB 조작")))
                .andExpect(jsonPath("$.status").value("TAMPERED"));
    }

    @Test
    void 이전_버전_티켓은_TAMPERED() throws Exception {
        RecordRequestDto ticket = sold();
        when(blockRecordAccess.verifyRecord(any())).thenReturn(new BlockVerifyResultDto(false, 4L, "newer"));

        call(CheckinApi.VERIFY, verifyOf(ticket))
                .andExpect(jsonPath("$.status").value("TAMPERED"))
                .andExpect(jsonPath("$.reason").value("최신 티켓이 아닙니다 (블록체인 최신 v4)"));
    }

    @Test
    void 블록_확정_전이면_사본으로_VERIFIED() throws Exception {
        RecordRequestDto ticket = sold();
        when(blockRecordAccess.verifyRecord(any())).thenReturn(new BlockVerifyResultDto(false, 1L, "issue-hash"));

        call(CheckinApi.VERIFY, verifyOf(ticket)).andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    @Test
    void 발급_기록이_없는_티켓은_TAMPERED_노드_응답_부족은_UNKNOWN() throws Exception {
        call(CheckinApi.VERIFY, new VerifyRequestDto("no-such-key", 1L, "x"))
                .andExpect(jsonPath("$.status").value("TAMPERED"));

        RecordRequestDto ticket = sold();
        when(blockRecordAccess.verifyRecord(any()))
                .thenThrow(new FairChainException(ErrorCode.NODE_UNAVAILABLE, "down"));
        call(CheckinApi.VERIFY, verifyOf(ticket)).andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    // ── SS-05 목록 검증 ─────────────────────────────────

    @Test
    void 목록은_내가_주인인_최신_기록만_확정된_것은_노드_대조() throws Exception {
        issueSeats(2);
        RecordRequestDto mine = record(RecordType.PURCHASE, 1, 7L, 2);
        call(ReservationApi.RECORDS, mine);
        // 2번 좌석: 샀다가 취소 → 목록에서 빠져야 함
        call(ReservationApi.RECORDS, record(RecordType.PURCHASE, 2, 7L, 2));
        call(ReservationApi.RECORDS, record(RecordType.CANCEL, 2, 7L, 3));

        blockRecordService.confirm(new com.fairchain.nodeapi.dto.BlockRecordDto(mine.recordKey(), 2L, mine.recordHash()), 11L);
        when(nodeVerificationAccess.verifyBatch(any())).thenReturn(
                new BlockBatchVerifyResponseDto(List.of(new BlockVerifyItemResultDto(11L, true, true))));

        call(TicketVerificationApi.ANCHORS, new AnchorListRequestDto(7L, EVENT))
                .andExpect(jsonPath("$.anchors.length()").value(1))
                .andExpect(jsonPath("$.anchors[0].recordKey").value(mine.recordKey()))
                .andExpect(jsonPath("$.anchors[0].blockState").value("CONFIRMED"))
                .andExpect(jsonPath("$.anchors[0].nodeMatched").value(true))
                .andExpect(jsonPath("$.verificationAvailable").value(true));
    }

    // ── 도우미 ──────────────────────────────────────

    /** 1번 좌석을 회원 1에게 판매 (v1 발행 → v2 판매) */
    private RecordRequestDto sold() throws Exception {
        issueSeats(1);
        RecordRequestDto purchase = record(RecordType.PURCHASE, 1, 1L, 2);
        call(ReservationApi.RECORDS, purchase);
        return purchase;
    }

    private VerifyRequestDto verifyOf(RecordRequestDto r) {
        return new VerifyRequestDto(r.recordKey(), r.version(), r.recordHash());
    }

    private void issueSeats(int count) throws Exception {
        issueSeatsFor(EVENT, count);
    }

    private void issueSeatsFor(long eventId, int count) throws Exception {
        List<RecordRequestDto> records = new ArrayList<>();
        for (long seat = 1; seat <= count; seat++) {
            records.add(new RecordRequestDto(RecordType.ISSUE, eventId, null, "seat-" + seat,
                    hasher.recordKey(CLIENT + eventId, seat), 1L, hasher.hash(String.valueOf(eventId), String.valueOf(seat), "ISSUE")));
        }
        call(AdminApi.ISSUE_SEATS, new SeatIssueRequestDto(eventId, records)).andExpect(status().isOk());
    }

    private RecordRequestDto record(RecordType type, long seat, Long userId, long version) {
        return recordFor(EVENT, type, seat, userId, version);
    }

    private RecordRequestDto recordFor(long eventId, RecordType type, long seat, Long userId, long version) {
        return new RecordRequestDto(type, eventId, userId, "seat-" + seat, hasher.recordKey(CLIENT + eventId, seat),
                version, hasher.hash(String.valueOf(eventId), String.valueOf(seat), String.valueOf(userId), type.name()));
    }

    private ResultActions call(String path, Object body) throws Exception {
        return mvc.perform(post(path).header(FairChainHeaders.API_KEY, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
