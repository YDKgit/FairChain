package com.fairchain.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** C-0315 좌석 — 공연은 seatGradeId → SeatGrade.concertId로 찾는다 */
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long seatGradeId;

    @Column(nullable = false, length = 30)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    private Long reservedBy;

    private LocalDateTime reservedAt;

    /** 이 좌석 티켓 키의 최신 기록 버전 (진실의 원천) */
    @Column(nullable = false)
    private Long recordVersion;

    protected Seat() {}

    /** 좌석 등록 — ISSUE v1 */
    public Seat(Long seatGradeId, String seatNumber) {
        this.seatGradeId = seatGradeId;
        this.seatNumber = seatNumber;
        this.status = SeatStatus.EMPTY;
        this.recordVersion = 1L;
    }

    /** 기록이 하나 생길 때마다 호출 — 올린 버전을 돌려준다 */
    public long nextRecordVersion() {
        recordVersion = recordVersion + 1;
        return recordVersion;
    }

    /** 예매 — 버전도 올린다 */
    public void reserve(Long memberId, LocalDateTime now) {
        this.status = SeatStatus.RESERVED;
        this.reservedBy = memberId;
        this.reservedAt = now;
        nextRecordVersion();
    }

    /** 취소 — 재배정이 끝날 때까지 일반 예매 차단 */
    public void markCancelled() {
        this.status = SeatStatus.CANCELLED;
        this.reservedBy = null;
        this.reservedAt = null;
    }

    /** 일반 예매로 개방 */
    public void open() {
        this.status = SeatStatus.EMPTY;
    }

    public Long getId() {
        return id;
    }

    public Long getSeatGradeId() {
        return seatGradeId;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public Long getReservedBy() {
        return reservedBy;
    }

    public LocalDateTime getReservedAt() {
        return reservedAt;
    }

    public Long getRecordVersion() {
        return recordVersion;
    }
}
