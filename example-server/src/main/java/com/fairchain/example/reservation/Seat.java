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

    /** 취소된 시각 — CANCELLED(보류) 상태일 때만 */
    private LocalDateTime cancelledAt;

    /** 이 취소표를 제안 중인 재배정 회차 (없으면 다음 제안 시각까지 보류) */
    private Long reallocationRoundId;

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
        cancelledAt = null;
        reallocationRoundId = null;
        return ++recordVersion;
    }

    /** 취소 — 재배정이 끝날 때까지 일반 예매를 막는다 (보류) */
    public long release() {
        status = SeatStatus.CANCELLED;
        reservedBy = null;
        reservedAt = null;
        cancelledAt = LocalDateTime.now();
        reallocationRoundId = null;
        return ++recordVersion;
    }

    /** 입장 기록 */
    public long nextVersion() {
        return ++recordVersion;
    }

    /** 재배정 회차에 넣는다 */
    public void joinRound(Long roundId) {
        this.reallocationRoundId = roundId;
    }

    /** 재배정할 사람이 없거나 기간이 끝나면 다시 일반 판매 */
    public void reopen() {
        status = SeatStatus.EMPTY;
        cancelledAt = null;
        reallocationRoundId = null;
    }

    public Long getId() { return id; }

    public Long getConcertId() { return concertId; }

    public Long getSeatGradeId() { return seatGradeId; }

    public String getSeatNumber() { return seatNumber; }

    public SeatStatus getStatus() { return status; }

    public Long getReservedBy() { return reservedBy; }

    public LocalDateTime getReservedAt() { return reservedAt; }

    public LocalDateTime getCancelledAt() { return cancelledAt; }

    public Long getReallocationRoundId() { return reallocationRoundId; }

    public Long getRecordVersion() { return recordVersion; }
}
