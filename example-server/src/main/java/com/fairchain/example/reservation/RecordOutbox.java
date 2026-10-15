package com.fairchain.example.reservation;

import com.fairchain.api.dto.RecordType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** FairChain에 보낼 기록. 예매와 같은 트랜잭션에 저장해 두고 따로 보낸다 (FairChain이 잠시 꺼져도 잃지 않음) */
@Entity
@Table(name = "record_outbox")
public class RecordOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 같은 좌석의 기록은 저장 순서대로 보낸다 */
    @Column(nullable = false)
    private Long seatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecordType type;

    /** RecordRequestDto JSON */
    @Column(nullable = false, length = 2000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime sentAt;

    /** FairChain 응답 — 매진 후 기록이었는지 (취소면 재배정 판단) */
    private Boolean soldOut;

    @Column(length = 500)
    private String lastError;

    protected RecordOutbox() {}

    public RecordOutbox(Long seatId, RecordType type, String payload) {
        this.seatId = seatId;
        this.type = type;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public void sent(boolean soldOut) {
        status = OutboxStatus.SENT;
        sentAt = LocalDateTime.now();
        this.soldOut = soldOut;
        lastError = null;
    }

    public void retryLater(String error) {
        retryCount++;
        lastError = truncate(error);
    }

    public void failed(String error) {
        status = OutboxStatus.FAILED;
        lastError = truncate(error);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }

    public Long getId() { return id; }

    public Long getSeatId() { return seatId; }

    public RecordType getType() { return type; }

    public String getPayload() { return payload; }

    public OutboxStatus getStatus() { return status; }

    public int getRetryCount() { return retryCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getSentAt() { return sentAt; }

    public Boolean getSoldOut() { return soldOut; }

    public String getLastError() { return lastError; }
}
