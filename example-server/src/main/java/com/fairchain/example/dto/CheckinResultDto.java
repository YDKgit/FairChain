package com.fairchain.example.dto;

/** C-0402 체크인 결과 */
public record CheckinResultDto(boolean success, String reason) {

    public static CheckinResultDto fail(String reason) {
        return new CheckinResultDto(false, reason);
    }
}
