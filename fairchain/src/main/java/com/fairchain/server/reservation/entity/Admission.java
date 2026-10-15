package com.fairchain.server.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 입장 기록 — "입장한 적이 있다"는 영구 기록. 재입장이면 새 행 추가 */
@Entity
@Table(name = "admission", indexes = {
        @Index(name = "idx_user_event", columnList = "event_id, user_id"),
        @Index(name = "idx_candidates", columnList = "event_id, status, sequence_number")
})
public class Admission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "sequence_number", nullable = false)
    private Long sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdmissionStatus status;

    @Column(name = "admitted_at", nullable = false)
    private LocalDateTime admittedAt;

    protected Admission() {}

    public static Admission admit(Long userId, Long eventId, Long sequenceNumber) {
        Admission admission = new Admission();
        admission.userId = userId;
        admission.eventId = eventId;
        admission.sequenceNumber = sequenceNumber;
        admission.status = AdmissionStatus.ADMITTED;
        admission.admittedAt = LocalDateTime.now();
        return admission;
    }

    public void purchase() {
        status = AdmissionStatus.PURCHASED;
    }

    public void cancel() {
        status = AdmissionStatus.CANCELLED;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getEventId() {
        return eventId;
    }

    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    public AdmissionStatus getStatus() {
        return status;
    }

    public LocalDateTime getAdmittedAt() {
        return admittedAt;
    }
}
