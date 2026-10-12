package com.fairchain.server.block.entity;

import com.fairchain.api.dto.BlockState;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/** 예제서버가 보낸 기록의 사본 — 목록 검증 · 블록 기록 재시도 기준 */
@Entity
@Table(name = "anchor_record",
        uniqueConstraints = @UniqueConstraint(name = "uq_key_version", columnNames = {"record_key", "version"}),
        indexes = @Index(name = "idx_user_list", columnList = "user_id, event_id"))
public class AnchorRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_server_id", nullable = false)
    private String clientId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    /** 발행(ISSUE) 때는 없음 */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "record_key", nullable = false, length = 64)
    private String recordKey;

    @Column(nullable = false)
    private Long version;

    @Column(name = "record_hash", nullable = false, length = 64)
    private String recordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecordType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_state", nullable = false)
    private BlockState blockState;

    /** CONFIRMED 후 기록 */
    @Column(name = "block_index")
    private Long blockIndex;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AnchorRecord() {}

    public static AnchorRecord pending(String clientId, RecordRequestDto dto) {
        AnchorRecord record = new AnchorRecord();
        record.clientId = clientId;
        record.eventId = dto.eventId();
        record.userId = dto.userId();
        record.recordKey = dto.recordKey();
        record.version = dto.version();
        record.recordHash = dto.recordHash();
        record.type = dto.type();
        record.blockState = BlockState.PENDING;
        record.createdAt = LocalDateTime.now();
        return record;
    }

    public void confirm(Long blockIndex) {
        this.blockState = BlockState.CONFIRMED;
        this.blockIndex = blockIndex;
    }

    public boolean isConfirmed() {
        return blockState == BlockState.CONFIRMED;
    }

    public Long getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public Long getEventId() {
        return eventId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getRecordKey() {
        return recordKey;
    }

    public Long getVersion() {
        return version;
    }

    public String getRecordHash() {
        return recordHash;
    }

    public RecordType getType() {
        return type;
    }

    public BlockState getBlockState() {
        return blockState;
    }

    public Long getBlockIndex() {
        return blockIndex;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
