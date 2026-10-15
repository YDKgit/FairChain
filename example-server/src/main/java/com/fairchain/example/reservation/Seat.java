package com.fairchain.example.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 좌석. recordVersion은 이 좌석 키로 FairChain에 남긴 최신 기록 버전 (발행 1 → 판매 2 → …) */
@Entity
@Table(name = "seat")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long concertId;

    @Column(nullable = false)
    private Long seatGradeId;

    @Column(nullable = false, length = 20)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    private Long reservedBy;

    private LocalDateTime reservedAt;

    @Column(nullable = false)
    private Long recordVersion;

    protected Seat() {}

    /** 좌석 사전 등록 — 발행(ISSUE) 기록이 v1 */
    public Seat(Long concertId, Long seatGradeId, String seatNumber) {
        this.concertId = concertId;
        this.seatGradeId = seatGradeId;
        this.seatNumber = seatNumber;
        this.status = SeatStatus.EMPTY;
        this.recordVersion = 1L;
    }

    /** 판매 — 새 기록 버전을 돌려준다 */
    public long reserve(Long memberId) {
        status = SeatStatus.RESERVED;
        reservedBy = memberId;
        reservedAt = LocalDateTime.now();
        return ++recordVersion;
    }

    /** 취소 — 재배정이 끝날 때까지 일반 예매를 막는다 */
    public long release() {
        status = SeatStatus.CANCELLED;
        reservedBy = null;
        reservedAt = null;
        return ++recordVersion;
    }

    /** 입장 기록 */
    public long nextVersion() {
        return ++recordVersion;
    }

    /** 재배정할 사람이 없으면 다시 일반 판매 */
    public void reopen() {
        status = SeatStatus.EMPTY;
    }

    public Long getId() { return id; }

    public Long getConcertId() { return concertId; }

    public Long getSeatGradeId() { return seatGradeId; }

    public String getSeatNumber() { return seatNumber; }

    public SeatStatus getStatus() { return status; }

    public Long getReservedBy() { return reservedBy; }

    public LocalDateTime getReservedAt() { return reservedAt; }

    public Long getRecordVersion() { return recordVersion; }
}
