package com.fairchain.server.block.service;

import com.fairchain.api.dto.BlockState;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.server.block.repository.AnchorRecordRepository;
import com.fairchain.server.block.requester.ConfirmedLedger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 확정된 블록 순서 = CONFIRMED 기록 사본을 블록 번호 순으로 */
@Component
public class AnchorConfirmedLedger implements ConfirmedLedger {

    private final AnchorRecordRepository anchorRecordRepository;

    public AnchorConfirmedLedger(AnchorRecordRepository anchorRecordRepository) {
        this.anchorRecordRepository = anchorRecordRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public long height() {
        Long max = anchorRecordRepository.findMaxConfirmedBlockIndex();
        return max == null ? 0 : max;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConfirmedBlock> between(long from, long to) {
        return anchorRecordRepository.findByBlockStateAndBlockIndexBetweenOrderByBlockIndexAsc(BlockState.CONFIRMED, from, to)
                .stream()
                .map(a -> new ConfirmedBlock(a.getBlockIndex(),
                        new BlockRecordDto(a.getRecordKey(), a.getVersion(), a.getRecordHash())))
                .toList();
    }
}
