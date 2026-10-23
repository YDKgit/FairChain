package com.fairchain.example.checkin;

import com.fairchain.api.CheckinApi;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifyRequestDto;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.http.HttpRequester;
import com.fairchain.example.common.TicketFingerprint;
import com.fairchain.example.reservation.Ticket;
import org.springframework.stereotype.Component;

/** 티켓 지문을 계산해 /api/checkin/verify로 전송 (C-0406) */
@Component
public class CheckinRequester implements CheckinAccess {

    private final HttpRequester httpRequester;
    private final TicketFingerprint fingerprint;

    public CheckinRequester(HttpRequester httpRequester, TicketFingerprint fingerprint) {
        this.httpRequester = httpRequester;
        this.fingerprint = fingerprint;
    }

    @Override
    public VerifyResultDto verify(Ticket ticket) {
        String hash = fingerprint.recordHash(ticket.getConcertId(), ticket.getSeatId(), ticket.getMemberId(),
                TicketFingerprint.statusOf(RecordType.PURCHASE), ticket.getVersion());
        VerifyRequestDto dto = new VerifyRequestDto(fingerprint.recordKey(ticket.getSeatId()), ticket.getVersion(), hash);
        return httpRequester.post(CheckinApi.VERIFY, dto, VerifyResultDto.class);
    }
}
