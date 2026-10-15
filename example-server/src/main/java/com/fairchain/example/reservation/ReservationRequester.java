package com.fairchain.example.reservation;

import com.fairchain.api.ReservationApi;
import com.fairchain.api.dto.AdmissionVerifyDto;
import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.http.HttpRequester;
import org.springframework.stereotype.Component;

/** 예매 요청을 FairChain ReservationApi 경로로 전송 (C-0310) */
@Component
public class ReservationRequester implements ReservationAccess {

    private final HttpRequester httpRequester;

    public ReservationRequester(HttpRequester httpRequester) {
        this.httpRequester = httpRequester;
    }

    @Override
    public VerifyResultDto verifyAdmission(Long userId, Long eventId) {
        return httpRequester.post(ReservationApi.VERIFY_ADMISSION, new AdmissionVerifyDto(userId, eventId), VerifyResultDto.class);
    }

    @Override
    public RecordResponseDto registerRecord(RecordRequestDto record) {
        return httpRequester.post(ReservationApi.RECORDS, record, RecordResponseDto.class);
    }

    @Override
    public ReallocationListDto getReallocationCandidates(Long eventId) {
        return httpRequester.get(ReservationApi.reallocationCandidatesPath(eventId), ReallocationListDto.class);
    }
}
