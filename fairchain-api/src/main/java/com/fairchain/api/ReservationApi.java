package com.fairchain.api;

import com.fairchain.api.dto.AdmissionVerifyDto;
import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.VerifyResultDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** SS-03 예매 + 모든 서브시스템 공용 기록 등록 — ReservationController가 구현 */
public interface ReservationApi {

    String VERIFY_ADMISSION = "/api/admission/verify";
    String RECORDS = "/api/records";
    String REALLOCATION_CANDIDATES = "/api/events/{eventId}/reallocation-candidates";

    /** 예매 전 입장 권한 확인 — 권한 있으면 VERIFIED */
    @PostMapping(VERIFY_ADMISSION)
    VerifyResultDto verifyAdmission(@RequestBody AdmissionVerifyDto dto);

    /** ISSUE(SS-06) · PURCHASE/CANCEL(SS-03) · CHECKIN(SS-04) 공용 */
    @PostMapping(RECORDS)
    RecordResponseDto registerRecord(@RequestBody RecordRequestDto dto);

    @GetMapping(REALLOCATION_CANDIDATES)
    ReallocationListDto getReallocationCandidates(@PathVariable("eventId") Long eventId);

    static String reallocationCandidatesPath(Long eventId) {
        return REALLOCATION_CANDIDATES.replace("{eventId}", String.valueOf(eventId));
    }
}
