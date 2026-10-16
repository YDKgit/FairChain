package com.fairchain.example.entity;

import com.fairchain.api.dto.RecordRequestDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * C-0318 기록 전송 대기열 (Outbox) — DB 변경과 같은 트랜잭션에 저장하고 비동기로 FairChain에 보낸다.
 * payload는 전송할 RecordRequestDto 그대로(지문 포함) json 컬럼에 저장한다.
 */
@Entity
@Table(name = "record_outbox")
public class RecordOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "json")
    private RecordRequestDto payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected RecordOutbox() {}

    public RecordOutbox(RecordRequestDto payload, LocalDateTime createdAt) {
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
        this.createdAt = createdAt;
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
    }

    public void increaseRetry() {
        this.retryCount++;
    }

    /** 이 기록(좌석 키 · 버전)인지 */
    public boolean isFor(String recordKey, Long version) {
        return payload.recordKey().equals(recordKey) && payload.version().equals(version);
    }

    public Long getId() {
        return id;
    }

    public RecordRequestDto getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
