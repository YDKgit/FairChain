package com.fairchain.example.operator;

import com.fairchain.api.AdminApi;
import com.fairchain.api.dto.ApiKeyDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.SeatIssueRequestDto;
import com.fairchain.api.http.HttpRequester;
import com.fairchain.example.common.TicketFingerprint;
import com.fairchain.example.reservation.Seat;
import org.springframework.stereotype.Component;

import java.util.List;

/** 좌석마다 키 · 지문을 계산해 SeatIssueRequestDto 전송 (C-0606) */
@Component
public class AdminRequester implements AdminAccess {

    private final HttpRequester httpRequester;
    private final TicketFingerprint fingerprint;

    public AdminRequester(HttpRequester httpRequester, TicketFingerprint fingerprint) {
        this.httpRequester = httpRequester;
        this.fingerprint = fingerprint;
    }

    @Override
    public ApiKeyDto getKeys() {
        return httpRequester.get(AdminApi.KEYS, ApiKeyDto.class);
    }

    @Override
    public void issueSeats(Long concertId, List<Seat> seats) {
        List<RecordRequestDto> records = seats.stream()
                .map(s -> fingerprint.record(RecordType.ISSUE, concertId, s.getId(), null, s.getRecordVersion()))
                .toList();
        httpRequester.post(AdminApi.ISSUE_SEATS, new SeatIssueRequestDto(concertId, records), Void.class);
    }
}
