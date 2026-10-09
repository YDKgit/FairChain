package com.fairchain.api;

import com.fairchain.api.dto.VerifyRequestDto;
import com.fairchain.api.dto.VerifyResultDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** SS-04 체크인 검증 — FC_CheckinController가 구현. 입장 기록은 ReservationApi.registerRecord(CHECKIN) */
public interface CheckinApi {

    String VERIFY = "/api/checkin/verify";

    @PostMapping(VERIFY)
    VerifyResultDto verify(@RequestBody VerifyRequestDto dto);
}
