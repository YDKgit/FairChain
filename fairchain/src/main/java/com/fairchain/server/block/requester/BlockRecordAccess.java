package com.fairchain.server.block.requester;

import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;

/** 노드 접근 — Service는 이 인터페이스만 안다 (HTTP 구현은 BlockRecordRequester) */
public interface BlockRecordAccess {

    BlockResultDto submitRecord(BlockRecordDto dto);

    /** 노드 3대 중 2대 이상 같은 결과. 다수가 없으면 NODE_UNAVAILABLE */
    BlockVerifyResultDto verifyRecord(BlockVerifyDto dto);
}
