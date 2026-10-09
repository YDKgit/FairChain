package com.fairchain.nodeapi;

import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** FairChain → 노드: 지문 기록 · 단건 검증 — NodeController가 구현 */
public interface BlockRecordApi {

    String RECORDS = "/node/records";
    String VERIFY = "/node/records/verify";

    /** 블록 기록 요청 — 받은 노드가 블록을 만들어 다른 노드에 제안하고 2/3 동의 시 확정 */
    @PostMapping(RECORDS)
    BlockResultDto submitRecord(@RequestBody BlockRecordDto dto);

    /** 체크인 검증 — 그 좌석 키의 최신 지문과 비교 */
    @PostMapping(VERIFY)
    BlockVerifyResultDto verifyRecord(@RequestBody BlockVerifyDto dto);
}
