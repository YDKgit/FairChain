package com.fairchain.server.block.requester;

import com.fairchain.nodeapi.dto.BlockBatchVerifyRequestDto;
import com.fairchain.nodeapi.dto.BlockBatchVerifyResponseDto;

public interface NodeVerificationAccess {

    /** 블록별로 2대 이상 같은 결과만 담는다. 다수가 없는 블록은 결과에서 빠짐 */
    BlockBatchVerifyResponseDto verifyBatch(BlockBatchVerifyRequestDto dto);
}
