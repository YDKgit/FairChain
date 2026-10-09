package com.fairchain.nodeapi;

import com.fairchain.nodeapi.dto.BlockBatchVerifyRequestDto;
import com.fairchain.nodeapi.dto.BlockBatchVerifyResponseDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** FairChain → 노드: 목록 조회용 묶음 검증 (SS-05) — NodeController가 구현 */
public interface NodeVerificationApi {

    String VERIFY_BATCH = "/node/records/verify-batch";

    /** 블록 번호로 바로 꺼내 지문 · 블록 해시 · 이전 블록 연결을 확인 */
    @PostMapping(VERIFY_BATCH)
    BlockBatchVerifyResponseDto verifyBatch(@RequestBody BlockBatchVerifyRequestDto dto);
}
