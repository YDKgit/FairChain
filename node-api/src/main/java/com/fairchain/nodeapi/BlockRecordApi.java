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

    /**
     * 블록 기록 요청 — 받은 노드가 자기 체인 끝에 블록을 추가한다. 노드끼리는 통신하지 않는다.
     * FairChain이 노드 3대에 같은 기록을 각각 보내고, 2대 이상이 같은 결과를 돌려주면 확정으로 본다.
     * 같은 기록을 다시 받으면 새 블록 없이 기존 블록 번호를 돌려준다.
     */
    @PostMapping(RECORDS)
    BlockResultDto submitRecord(@RequestBody BlockRecordDto dto);

    /** 체크인 검증 — 그 좌석 키의 최신 지문과 비교 */
    @PostMapping(VERIFY)
    BlockVerifyResultDto verifyRecord(@RequestBody BlockVerifyDto dto);
}
