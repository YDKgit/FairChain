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
     * 블록 기록 요청 — 아무 리더 층 노드에나 보내면 된다. 팔로워는 Raft 리더에게 넘기고, 리더가 블록을 만들어
     * Raft 로그로 복제한다. 과반이 저장하면 확정. 같은 기록(recordKey + version)을 다시 보내면 기존 블록 번호를 돌려준다.
     */
    @PostMapping(RECORDS)
    BlockResultDto submitRecord(@RequestBody BlockRecordDto dto);

    /** 체크인 검증 — 그 좌석 키의 최신 지문과 비교 */
    @PostMapping(VERIFY)
    BlockVerifyResultDto verifyRecord(@RequestBody BlockVerifyDto dto);
}
