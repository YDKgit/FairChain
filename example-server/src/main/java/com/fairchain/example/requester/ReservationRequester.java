package com.fairchain.example.requester;

import com.fairchain.api.ReservationApi;
import com.fairchain.api.dto.AdmissionVerifyDto;
import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.http.HttpRequester;
import com.fairchain.example.entity.Ticket;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * C-0310 예매 Requester — 지문 계산은 RecordFactory.
 * Idempotency-Key("record-{recordKey}-{version}")는 HttpRequester가 헤더를 받지 않아 보내지 못한다 (fairchain-api 요청사항).
 */
@Component
@Profile("!fairchain-mock")
public class ReservationRequester implements ReservationAccess {

    private final HttpRequester httpRequester;
    private final RecordFactory recordFactory;

    public ReservationRequester(HttpRequester httpRequester, RecordFactory recordFactory) {
        this.httpRequester = httpRequester;
        this.recordFactory = recordFactory;
    }

    @Override
    public VerifyResultDto verifyAdmission(Long userId, Long eventId) {
        return FairChainCall.call("입장 권한 확인", () -> httpRequester.post(
                ReservationApi.VERIFY_ADMISSION, new AdmissionVerifyDto(userId, eventId), VerifyResultDto.class));
    }

    @Override
    public RecordResponseDto registerRecord(Ticket ticket, RecordType type) {
        return registerRecord(buildRecord(ticket, type));
    }

    @Override
    public RecordRequestDto buildRecord(Ticket ticket, RecordType type) {
        return recordFactory.build(ticket, type);
    }

    @Override
    public RecordResponseDto registerRecord(RecordRequestDto dto) {
        return FairChainCall.call("기록 등록(" + dto.type() + ")", () -> httpRequester.post(
                ReservationApi.RECORDS, dto, RecordResponseDto.class));
    }

    @Override
    public ReallocationListDto getReallocationCandidates(Long eventId) {
        return FairChainCall.call("재배정 명단 조회", () -> httpRequester.get(
                ReservationApi.reallocationCandidatesPath(eventId), ReallocationListDto.class));
    }
}
