package com.fairchain.example.requester;

import com.fairchain.api.AdminApi;
import com.fairchain.api.dto.ApiKeyDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.SeatIssueRequestDto;
import com.fairchain.api.http.HttpRequester;
import com.fairchain.example.entity.Seat;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/** C-0606 운영 관리 Requester */
@Component
@Profile("!fairchain-mock")
public class AdminRequester implements AdminAccess {

    private final HttpRequester httpRequester;
    private final RecordFactory recordFactory;

    public AdminRequester(HttpRequester httpRequester, RecordFactory recordFactory) {
        this.httpRequester = httpRequester;
        this.recordFactory = recordFactory;
    }

    @Override
    public ApiKeyDto getKeys() {
        return FairChainCall.call("API 키 조회", () -> httpRequester.get(AdminApi.KEYS, ApiKeyDto.class));
    }

    @Override
    public void issueSeats(Long concertId, List<Seat> seats) {
        List<RecordRequestDto> records = seats.stream()
                .map(seat -> recordFactory.buildIssue(concertId, seat))
                .toList();
        SeatIssueRequestDto dto = new SeatIssueRequestDto(concertId, records);
        FairChainCall.call("좌석 등록", () -> httpRequester.post(AdminApi.ISSUE_SEATS, dto, Void.class));
    }
}
