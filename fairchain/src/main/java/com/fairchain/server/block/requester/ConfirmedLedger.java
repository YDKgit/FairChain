package com.fairchain.server.block.requester;

import com.fairchain.nodeapi.dto.BlockRecordDto;

import java.util.List;

/**
 * FairChain이 확정한 블록 순서 (기록 사본 anchor_record 기준).
 * 꺼졌다 켜진 노드 · 응답을 놓친 노드에 빠진 기록을 같은 순서로 다시 보낼 때 쓴다.
 */
public interface ConfirmedLedger {

    /** 확정된 마지막 블록 번호. 아직 없으면 0 */
    long height();

    /** 블록 번호 from~to(포함)의 확정 기록, 번호 순 */
    List<ConfirmedBlock> between(long from, long to);

    record ConfirmedBlock(long blockIndex, BlockRecordDto record) {}
}
