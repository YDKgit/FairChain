package com.fairchain.example.requester;

import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.example.entity.Ticket;

/** C-0405 체크인 FairChain 접근 */
public interface CheckinAccess {

    /** 티켓의 현재 상태로 지문을 계산해 최신 기록과 비교 */
    VerifyResultDto verify(Ticket ticket);

    /** CHECKIN 기록 전송 */
    void recordCheckin(Ticket ticket);

    /** 보완 — 전송 실패 시 Outbox에 넣을 CHECKIN 기록 스냅샷 */
    RecordRequestDto buildRecord(Ticket ticket);
}
