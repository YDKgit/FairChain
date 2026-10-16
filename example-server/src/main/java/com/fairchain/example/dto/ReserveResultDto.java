package com.fairchain.example.dto;

/** C-0302 예매 결과 — 업무상 실패도 success=false + reason */
public record ReserveResultDto(boolean success, Long ticketId, String reason) {

    public static ReserveResultDto success(Long ticketId) {
        return new ReserveResultDto(true, ticketId, null);
    }

    public static ReserveResultDto fail(String reason) {
        return new ReserveResultDto(false, null, reason);
    }
}
