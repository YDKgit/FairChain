package com.fairchain.example.reservation;

import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.VerifyResultDto;

/** FairChain 예매 호출 (C-0309) */
public interface ReservationAccess {

    /** 대기열을 통과해 입장 권한이 있는지 */
    VerifyResultDto verifyAdmission(Long userId, Long eventId);

    /** 발행 · 판매 · 취소 · 입장 기록 등록 (recordKey · recordHash는 TicketFingerprint가 계산해 둔 값) */
    RecordResponseDto registerRecord(RecordRequestDto record);

    ReallocationListDto getReallocationCandidates(Long eventId);
}
