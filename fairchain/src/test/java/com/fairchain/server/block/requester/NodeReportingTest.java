package com.fairchain.server.block.requester;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.nodeapi.BlockRecordApi;
import com.fairchain.nodeapi.NodeVerificationApi;
import com.fairchain.nodeapi.dto.BlockBatchVerifyRequestDto;
import com.fairchain.nodeapi.dto.BlockBatchVerifyResponseDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyItemDto;
import com.fairchain.nodeapi.dto.BlockVerifyItemResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노드 답 다수결과 신고 (노드 구조 미정사항 13 · 14번): 다수와 다른 노드가 뒤처짐(BEHIND)이면 그냥 두고, 아니면 신고만 한다.
 */
class NodeReportingTest {

    private static final String H = "a".repeat(64);
    private static final BlockVerifyDto TICKET = new BlockVerifyDto("b".repeat(64), 2L, H);

    private final HttpRequester[] nodes = {mock(HttpRequester.class), mock(HttpRequester.class), mock(HttpRequester.class)};
    private final NodeDivergenceReporter reporter = mock(NodeDivergenceReporter.class);

    private BlockRecordRequester records() {
        return new BlockRecordRequester(NodeDirectory.fixed(List.of(nodes)), reporter);
    }

    private void verifyAnswers(BlockVerifyResultDto... answers) {
        for (int i = 0; i < 3; i++) {
            when(nodes[i].post(eq(BlockRecordApi.VERIFY), any(), eq(BlockVerifyResultDto.class))).thenReturn(answers[i]);
        }
    }

    /** 기록이 오가는 동안 노드마다 마지막 블록 번호가 다를 수 있다 — 같은 답이면 다수결이 나와야 한다 */
    @Test
    void sameAnswerWithDifferentHeightsStillMakesAMajority() {
        verifyAnswers(new BlockVerifyResultDto(true, 2L, H, 10L), new BlockVerifyResultDto(true, 2L, H, 11L),
                new BlockVerifyResultDto(true, 2L, H, 9L));
        BlockVerifyResultDto result = records().verifyRecord(TICKET);
        assertTrue(result.valid());
        assertEquals(2L, result.latestVersion());
        verify(reporter, never()).report(any(), anyInt(), any(), any());
    }

    @Test
    void aMinorityNodeThatIsBehindIsNotReported() {
        verifyAnswers(new BlockVerifyResultDto(true, 2L, H, 10L), new BlockVerifyResultDto(true, 2L, H, 10L),
                new BlockVerifyResultDto(false, 1L, "c".repeat(64), 7L));
        assertTrue(records().verifyRecord(TICKET).valid());
        verify(reporter, never()).report(any(), anyInt(), any(), any());
    }

    @Test
    void aMinorityNodeThatIsNotBehindIsReported() {
        verifyAnswers(new BlockVerifyResultDto(true, 2L, H, 10L), new BlockVerifyResultDto(false, 2L, "e".repeat(64), 10L),
                new BlockVerifyResultDto(true, 2L, H, 10L));
        assertTrue(records().verifyRecord(TICKET).valid());
        verify(reporter).report(any(), eq(1), isNull(), eq(TICKET.recordKey()));
    }

    @Test
    void batchReportsOnlyNodesThatHaveTheBlock() {
        BlockVerifyItemDto item = new BlockVerifyItemDto(5L, "b".repeat(64), 2L, H);
        BlockVerifyItemResultDto ok = new BlockVerifyItemResultDto(5L, true, true);
        BlockVerifyItemResultDto bad = new BlockVerifyItemResultDto(5L, false, false);
        when(nodes[0].post(eq(NodeVerificationApi.VERIFY_BATCH), any(), eq(BlockBatchVerifyResponseDto.class)))
                .thenReturn(new BlockBatchVerifyResponseDto(List.of(ok), 9L));
        when(nodes[1].post(eq(NodeVerificationApi.VERIFY_BATCH), any(), eq(BlockBatchVerifyResponseDto.class)))
                .thenReturn(new BlockBatchVerifyResponseDto(List.of(bad), 4L));      // 5번이 아직 없다 → 뒤처짐
        when(nodes[2].post(eq(NodeVerificationApi.VERIFY_BATCH), any(), eq(BlockBatchVerifyResponseDto.class)))
                .thenReturn(new BlockBatchVerifyResponseDto(List.of(ok), 9L));
        NodeVerificationRequester requester = new NodeVerificationRequester(NodeDirectory.fixed(List.of(nodes)), reporter);

        BlockBatchVerifyResponseDto result = requester.verifyBatch(new BlockBatchVerifyRequestDto(List.of(item)));
        assertEquals(List.of(ok), result.results());
        verify(reporter, never()).report(any(), anyInt(), any(), any());

        when(nodes[1].post(eq(NodeVerificationApi.VERIFY_BATCH), any(), eq(BlockBatchVerifyResponseDto.class)))
                .thenReturn(new BlockBatchVerifyResponseDto(List.of(bad), 9L));      // 5번을 갖고 있는데 다르다 → 신고
        requester.verifyBatch(new BlockBatchVerifyRequestDto(List.of(item)));
        verify(reporter).report(any(), eq(1), eq(5L), eq(item.recordKey()));
    }
}
