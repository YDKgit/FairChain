package com.fairchain.server.reservation.entity;

public enum AdmissionStatus {
    /** 입장했지만 아직 안 삼 → 매진 시 재배정 후보 */
    ADMITTED,
    PURCHASED,
    CANCELLED
}
