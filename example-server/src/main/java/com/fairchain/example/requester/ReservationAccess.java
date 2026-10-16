package com.fairchain.example.requester;

import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.example.entity.Ticket;

/** C-0309 예매 FairChain 접근 */
public interface ReservationAccess {

    /** 예매 전 입장 권한 확인 — VERIFIED만 권한 있음 */
    VerifyResultDto verifyAdmission(Long userId, Long eventId);

    /** 티켓의 현재 상태로 기록 생성 후 전송 */
    RecordResponseDto registerRecord(Ticket ticket, RecordType type);

    /** 보완 — 전송 없이 기록 스냅샷만 생성 (Outbox 저장용) */
    RecordRequestDto buildRecord(Ticket ticket, RecordType type);

    /** 보완 — 이미 만든 기록 전송 (Outbox 재전송 · 취소 동기 전송) */
    RecordResponseDto registerRecord(RecordRequestDto dto);

    ReallocationListDto getReallocationCandidates(Long eventId);
}
