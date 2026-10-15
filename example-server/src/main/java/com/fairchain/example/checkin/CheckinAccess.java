package com.fairchain.example.checkin;

import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.example.reservation.Ticket;

/** FairChain 지문 검증 호출 (C-0405). 입장 기록은 다른 기록처럼 Outbox로 보낸다 */
public interface CheckinAccess {

    VerifyResultDto verify(Ticket ticket);
}
