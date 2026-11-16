package com.fairchain.server.block.service;

import com.fairchain.api.dto.BlockState;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.server.block.entity.AnchorRecord;
import com.fairchain.server.block.repository.AnchorRecordRepository;
import com.fairchain.server.block.repository.BlockQueueRepository;
import com.fairchain.server.block.requester.BlockRecordAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ⑥ 블록 기록 — 예매 응답을 막지 않도록 대기 줄에 넣고 따로 처리한다.
 * 노드에는 좌석 키 · 버전 · 지문만 간다 (개인정보 없음).
 */
@Service
public class BlockRecordService {

    private static final Logger log = LoggerFactory.getLogger(BlockRecordService.class);

    private final BlockQueueRepository blockQueueRepository;
    private final BlockRecordAccess blockRecordAccess;
    private final AnchorRecordRepository anchorRecordRepository;

    public BlockRecordService(BlockQueueRepository blockQueueRepository, BlockRecordAccess blockRecordAccess,
                              AnchorRecordRepository anchorRecordRepository) {
        this.blockQueueRepository = blockQueueRepository;
        this.blockRecordAccess = blockRecordAccess;
        this.anchorRecordRepository = anchorRecordRepository;
    }

    public void enqueueRecord(RecordRequestDto dto) {
        blockQueueRepository.enqueue(new BlockRecordDto(dto.recordKey(), dto.version(), dto.recordHash()));
    }

    /** 한 기록을 이어서 보내 보는 횟수 (노드 구조 변경안 11/06 6장 · 미정사항 6번: "재전송 3번") */
    static final int SUBMIT_ATTEMPTS = 3;
    private static final long RETRY_PAUSE_MILLIS = 200;

    /**
     * 대기 줄을 앞에서부터 처리. 한 기록을 3번까지 보내 보고, 그래도 안 되면 맨 앞에 되돌려 다음 주기에 다시 (순서 유지).
     * 같은 기록(recordKey + version)을 다시 보내면 노드는 이미 만든 블록 번호를 돌려주므로 블록이 둘 생기지 않는다.
     */
    @Scheduled(fixedDelayString = "${fairchain.block.process-interval-ms:500}")
    public void processPending() {
        BlockRecordDto dto;
        while ((dto = blockQueueRepository.dequeue()) != null) {
            BlockResultDto result = submitWithRetry(dto);
            if (!result.accepted()) {
                blockQueueRepository.pushFront(dto);
                log.warn("블록 기록 {}번 실패, 대기 줄 맨 앞에 되돌림 (다음 주기에 다시): {} v{}",
                        SUBMIT_ATTEMPTS, dto.recordKey(), dto.version());
                return;
            }
            confirm(dto, result.blockIndex());
        }
    }

    private BlockResultDto submitWithRetry(BlockRecordDto dto) {
        BlockResultDto result = new BlockResultDto(false, null);
        for (int attempt = 1; attempt <= SUBMIT_ATTEMPTS; attempt++) {
            try {
                result = blockRecordAccess.submitRecord(dto);
            } catch (RuntimeException e) {
                result = new BlockResultDto(false, null);
            }
            if (result != null && result.accepted()) {
                return result;
            }
            if (attempt < SUBMIT_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_PAUSE_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return result == null ? new BlockResultDto(false, null) : result;
    }

    @Transactional
    public void confirm(BlockRecordDto dto, Long blockIndex) {
        anchorRecordRepository.findByRecordKeyAndVersion(dto.recordKey(), dto.version())
                .ifPresent(record -> {
                    record.confirm(blockIndex);
                    anchorRecordRepository.save(record);
                });
    }

    /** 서버가 커밋 직후 · 대기 줄 넣기 전에 꺼졌던 경우 대비: 대기 줄이 비어 있으면 PENDING 사본을 다시 넣는다 */
    @EventListener(ApplicationReadyEvent.class)
    public void requeuePending() {
        if (blockQueueRepository.size() > 0) {
            return;
        }
        for (AnchorRecord record : anchorRecordRepository.findByBlockStateOrderByIdAsc(BlockState.PENDING)) {
            blockQueueRepository.enqueue(new BlockRecordDto(record.getRecordKey(), record.getVersion(), record.getRecordHash()));
        }
    }
}
