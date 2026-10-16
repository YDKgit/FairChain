package com.fairchain.example.requester;

import com.fairchain.api.dto.ApiKeyDto;
import com.fairchain.example.entity.Seat;

import java.util.List;

/** C-0605 운영 관리 FairChain 접근 */
public interface AdminAccess {

    ApiKeyDto getKeys();

    /** 좌석마다 ISSUE 기록 */
    void issueSeats(Long concertId, List<Seat> seats);
}
