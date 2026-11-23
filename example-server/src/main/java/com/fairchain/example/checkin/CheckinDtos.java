package com.fairchain.example.checkin;

/** 체크인 관리 DTO (CD-04-1) */
public final class CheckinDtos {

    private CheckinDtos() {}

    public record GateStaffRegisterDto(String loginId, String password, String name) {}

    public record StaffLoginDto(String loginId, String password) {}

    public record ScanRequestDto(String qrToken) {}

    /** @param seatNumber · concertTitle 확인용 (입장 허용일 때) */
    public record CheckinResultDto(boolean success, String reason, String concertTitle, String seatNumber) {

        static CheckinResultDto deny(String reason) {
            return new CheckinResultDto(false, reason, null, null);
        }

        /** 어느 공연 · 좌석의 티켓인지는 아는 거절 — 게이트 화면이 공연별로 모아 보여 준다 */
        static CheckinResultDto deny(String reason, String concertTitle, String seatNumber) {
            return new CheckinResultDto(false, reason, concertTitle, seatNumber);
        }
    }
}
