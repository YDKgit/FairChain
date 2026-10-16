package com.fairchain.example.requester;

import com.fairchain.api.CheckinApi;
import com.fairchain.api.ReservationApi;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyRequestDto;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.http.HttpRequester;
import com.fairchain.example.entity.Ticket;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** C-0406 체크인 Requester — 입장 기록은 ReservationApi.RECORDS(CHECKIN) */
@Component
@Profile("!fairchain-mock")
public class CheckinRequester implements CheckinAccess {

    private final HttpRequester httpRequester;
    private final RecordFactory recordFactory;

    public CheckinRequester(HttpRequester httpRequester, RecordFactory recordFactory) {
        this.httpRequester = httpRequester;
        this.recordFactory = recordFactory;
    }

    @Override
    public VerifyResultDto verify(Ticket ticket) {
        VerifyRequestDto dto = new VerifyRequestDto(
                recordFactory.recordKey(ticket.getSeatId()), ticket.getVersion(), recordFactory.recordHash(ticket));
        return FairChainCall.call("체크인 검증", () -> httpRequester.post(CheckinApi.VERIFY, dto, VerifyResultDto.class));
    }

    @Override
    public void recordCheckin(Ticket ticket) {
        RecordRequestDto dto = buildRecord(ticket);
        FairChainCall.call("입장 기록", () -> httpRequester.post(ReservationApi.RECORDS, dto, RecordResponseDto.class));
    }

    @Override
    public RecordRequestDto buildRecord(Ticket ticket) {
        return recordFactory.build(ticket, RecordType.CHECKIN);
    }
}
