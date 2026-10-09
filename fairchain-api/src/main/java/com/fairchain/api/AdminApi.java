package com.fairchain.api;

import com.fairchain.api.dto.ApiKeyDto;
import com.fairchain.api.dto.SeatIssueRequestDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** SS-06 운영 관리 — AdminController가 구현 */
public interface AdminApi {

    String KEYS = "/api/admin/keys";
    String ISSUE_SEATS = "/api/admin/seats";

    @GetMapping(KEYS)
    ApiKeyDto getKeys();

    /** 좌석 등록 — 좌석마다 ISSUE 기록 */
    @PostMapping(ISSUE_SEATS)
    void issueSeats(@RequestBody SeatIssueRequestDto dto);
}
