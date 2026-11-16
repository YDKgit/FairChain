package com.fairchain.nodeapi.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 새 필드를 더한 DTO가 JSON으로 그대로 오가고, 새 필드가 없는 예전 JSON도 읽히는지 확인한다.
 * (FairChain · 다른 노드가 한쪽만 먼저 바뀌어도 깨지지 않게)
 */
class DtoJsonTest {

    /** Spring Boot 기본 설정과 같게: 모르는 필드는 무시, 날짜 모듈 등록 */
    private final ObjectMapper json = JsonMapper.builder()
            .findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private <T> T roundTrip(T value, Class<T> type) throws Exception {
        return json.readValue(json.writeValueAsString(value), type);
    }

    private static final String H = "a".repeat(64);

    @Test
    void newFieldsSurviveARoundTrip() throws Exception {
        BlockResultDto result = new BlockResultDto(false, null, ReasonCode.NOT_LEADER);
        assertEquals(result, roundTrip(result, BlockResultDto.class));

        BlockVerifyResultDto verify = new BlockVerifyResultDto(true, 2L, H, 17L);
        assertEquals(verify, roundTrip(verify, BlockVerifyResultDto.class));

        BlockBatchVerifyResponseDto batch = new BlockBatchVerifyResponseDto(
                List.of(new BlockVerifyItemResultDto(3L, true, true)), 9L);
        assertEquals(batch, roundTrip(batch, BlockBatchVerifyResponseDto.class));

        BlockProposalDto block = new BlockProposalDto(1L, H, H, 1L, H, 1_700_000_000_000L, H, "A1", "c2ln");
        assertEquals(block, roundTrip(block, BlockProposalDto.class));

        BlockDto stored = new BlockDto(1L, H, H, 1L, H, LocalDateTime.of(2026, 11, 16, 12, 0), H, "A1", "c2ln");
        assertEquals(stored, roundTrip(stored, BlockDto.class));

        MemberAckDto ack = new MemberAckDto(false, ReasonCode.DIVERGED, 4L, H);
        assertEquals(ack, roundTrip(ack, MemberAckDto.class));

        DivergenceReportDto report = new DivergenceReportDto("B1", 7L, H, "fairchain");
        assertEquals(report, roundTrip(report, DivergenceReportDto.class));
        DivergenceReportDto memberReport = new DivergenceReportDto("A2", null, null, "A1", "http://a2:9011");
        assertEquals(memberReport, roundTrip(memberReport, DivergenceReportDto.class));
        assertEquals(new ReportAckDto(true, ReasonCode.OK), roundTrip(new ReportAckDto(true, ReasonCode.OK), ReportAckDto.class));
        assertEquals(new RestoreRequestDto("B1", 9L, H), roundTrip(new RestoreRequestDto("B1", 9L, H), RestoreRequestDto.class));
        assertEquals(new RestoreResultDto(true, "ok", 44L), roundTrip(new RestoreResultDto(true, "ok", 44L), RestoreResultDto.class));

        MemberJoinDto join = new MemberJoinDto("D1", "D", "http://10.0.0.4:9001", "MCowBQYDK2VwAyEA");
        assertEquals(join, roundTrip(join, MemberJoinDto.class));
        assertEquals(new MembershipResultDto(false, "busy"), roundTrip(new MembershipResultDto(false, "busy"), MembershipResultDto.class));

        VerdictDto verdict = new VerdictDto("B1", ReasonCode.DIVERGED, 7L, block, block, List.of("recordHash"),
                "EXCLUDED", "A1", 1_700_000_000_000L);
        NodeStatusDto status = new NodeStatusDto("A1", "A", "leader-layer", "LEADER", 3, "A1",
                List.of(new MemberInfoDto("A1", "A", "http://a:9001", "key", true, false)),
                List.of(), List.of("B1"), List.of(verdict), 12L, H, "MCowBQYDK2VwAyEA", "A1",
                List.of(new TeamMemberDto("A2", "http://a2:9011", 12L, H, ReasonCode.OK, 5L)), null, false);
        assertEquals(status, roundTrip(status, NodeStatusDto.class));
        assertEquals(new BlockHashDto(5L, H, 9L), roundTrip(new BlockHashDto(5L, H, 9L), BlockHashDto.class));
        TeamHelloDto hello = new TeamHelloDto("A2", "A", "http://a2:9011", 3L, H, ReasonCode.OK);
        assertEquals(hello, roundTrip(hello, TeamHelloDto.class));
    }

    @Test
    void oldJsonWithoutTheNewFieldsIsStillRead() throws Exception {
        BlockResultDto result = json.readValue("{\"accepted\":true,\"blockIndex\":3}", BlockResultDto.class);
        assertTrue(result.accepted());
        assertEquals(3L, result.blockIndex());
        assertNull(result.reason());

        BlockVerifyResultDto verify = json.readValue(
                "{\"valid\":true,\"latestVersion\":2,\"latestRecordHash\":\"" + H + "\"}", BlockVerifyResultDto.class);
        assertEquals(new BlockVerifyResultDto(true, 2L, H), verify);
        assertNull(verify.lastBlockIndex());

        BlockBatchVerifyResponseDto batch = json.readValue(
                "{\"results\":[{\"blockIndex\":1,\"matched\":true,\"confirmed\":false}]}", BlockBatchVerifyResponseDto.class);
        assertEquals(1, batch.results().size());
        assertNull(batch.lastBlockIndex());

        BlockProposalDto block = json.readValue("{\"blockIndex\":1,\"prevBlockHash\":\"" + H + "\",\"recordKey\":\"" + H
                + "\",\"version\":1,\"recordHash\":\"" + H + "\",\"timestamp\":5,\"blockHash\":\"" + H + "\"}",
                BlockProposalDto.class);
        assertEquals(new BlockProposalDto(1L, H, H, 1L, H, 5L, H), block);
    }

    @Test
    void oldConstructorsKeepTheirMeaning() {
        assertEquals(ReasonCode.OK, new BlockResultDto(true, 1L).reason());
        assertNull(new BlockResultDto(false, null).reason());
        assertEquals(ReasonCode.BEHIND, BlockResultDto.rejected(ReasonCode.BEHIND).reason());
    }
}
