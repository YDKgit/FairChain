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

/** C-0317 재배정 제안 — 매진 후 취소된 좌석을 대기 순번대로 한 명씩 제안 */
@Entity
@Table(name = "reallocation_offers")
public class ReallocationOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long concertId;

    @Column(nullable = false)
    private Long seatId;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long sequenceNumber;

    @Column(unique = true)
    private String offerToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OfferStatus status;

    private LocalDateTime expiresAt;

    protected ReallocationOffer() {}

    public ReallocationOffer(Long concertId, Long seatId, Long memberId, Long sequenceNumber) {
        this.concertId = concertId;
        this.seatId = seatId;
        this.memberId = memberId;
        this.sequenceNumber = sequenceNumber;
        this.status = OfferStatus.WAITING;
    }

    /** 알림 발송 — 응답 기한 시작 */
    public void offer(String offerToken, LocalDateTime expiresAt) {
        this.offerToken = offerToken;
        this.expiresAt = expiresAt;
        this.status = OfferStatus.OFFERED;
    }

    public void accept() {
        this.status = OfferStatus.ACCEPTED;
    }

    public void expire() {
        this.status = OfferStatus.EXPIRED;
    }

    public boolean isValidFor(Long concertId, Long seatId, Long memberId, LocalDateTime now) {
        return status == OfferStatus.OFFERED
                && this.concertId.equals(concertId)
                && this.seatId.equals(seatId)
                && this.memberId.equals(memberId)
                && expiresAt != null && expiresAt.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public Long getConcertId() {
        return concertId;
    }

    public Long getSeatId() {
        return seatId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    public String getOfferToken() {
        return offerToken;
    }

    public OfferStatus getStatus() {
        return status;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
