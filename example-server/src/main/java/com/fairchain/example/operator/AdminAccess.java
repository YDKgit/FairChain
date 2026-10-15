package com.fairchain.example.operator;

import com.fairchain.api.dto.ApiKeyDto;
import com.fairchain.example.reservation.Seat;

import java.util.List;

/** FairChain 운영 호출 (C-0605) */
public interface AdminAccess {

    ApiKeyDto getKeys();

    /** 좌석마다 발행(ISSUE v1) 기록 */
    void issueSeats(Long concertId, List<Seat> seats);
}
