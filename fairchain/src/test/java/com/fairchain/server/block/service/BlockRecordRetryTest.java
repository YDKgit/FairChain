package com.fairchain.server.block.service;

import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.server.block.repository.AnchorRecordRepository;
import com.fairchain.server.block.repository.BlockQueueRepository;
import com.fairchain.server.block.requester.BlockRecordAccess;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 블록 기록 재전송 3번 → 그래도 실패하면 대기 줄 맨 앞에 되돌림 (노드 구조 변경안 6장 · 미정사항 6번) */
class BlockRecordRetryTest {

    private final BlockQueueRepository queue = mock(BlockQueueRepository.class);
    private final BlockRecordAccess nodes = mock(BlockRecordAccess.class);
    private final AnchorRecordRepository anchors = mock(AnchorRecordRepository.class);
    private final BlockRecordService service = new BlockRecordService(queue, nodes, anchors);
    private final BlockRecordDto record = new BlockRecordDto("b".repeat(64), 1L, "c".repeat(64));

    @Test
    void aRecordIsSentUpToThreeTimesBeforeGoingBackToTheFrontOfTheQueue() {
        when(queue.dequeue()).thenReturn(record);
        when(nodes.submitRecord(any())).thenReturn(new BlockResultDto(false, null));

        service.processPending();

        verify(nodes, times(BlockRecordService.SUBMIT_ATTEMPTS)).submitRecord(record);
        verify(queue).pushFront(record);
    }

    @Test
    void aRecordThatGoesThroughOnTheThirdTryIsConfirmed() {
        when(queue.dequeue()).thenReturn(record, (BlockRecordDto) null);
        when(nodes.submitRecord(any())).thenReturn(new BlockResultDto(false, null), new BlockResultDto(false, null),
                new BlockResultDto(true, 7L));
        when(anchors.findByRecordKeyAndVersion(anyString(), anyLong())).thenReturn(Optional.empty());

        service.processPending();

        verify(nodes, times(3)).submitRecord(record);
        verify(queue, never()).pushFront(any());
        verify(anchors).findByRecordKeyAndVersion(record.recordKey(), record.version());
    }
}
