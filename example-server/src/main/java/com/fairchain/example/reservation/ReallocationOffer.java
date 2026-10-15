package com.fairchain.example.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/** 취소표 재배정 제안. FairChain 명단(최초 대기 순번순)대로 한 명씩 제한시간을 두고 제안한다 */
@Entity
@Table(name = "reallocation_offer")
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

    /** 명단 순서 (최초 대기 순번순) */
    @Column(nullable = false)
    private Long sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OfferStatus status;

    @Column(length = 36)
    private String offerToken;

    private LocalDateTime expiresAt;

    protected ReallocationOffer() {}

    public ReallocationOffer(Long concertId, Long seatId, Long memberId, Long sequenceNumber) {
        this.concertId = concertId;
        this.seatId = seatId;
        this.memberId = memberId;
        this.sequenceNumber = sequenceNumber;
        this.status = OfferStatus.WAITING;
    }

    public void offer(Duration ttl) {
        status = OfferStatus.OFFERED;
        offerToken = UUID.randomUUID().toString();
        expiresAt = LocalDateTime.now().plus(ttl);
    }

    public void accept() {
        status = OfferStatus.ACCEPTED;
    }

    public void expire() {
        status = OfferStatus.EXPIRED;
    }

    public boolean isUsableBy(Long memberId, LocalDateTime now) {
        return status == OfferStatus.OFFERED && this.memberId.equals(memberId) && now.isBefore(expiresAt);
    }

    public Long getId() { return id; }

    public Long getConcertId() { return concertId; }

    public Long getSeatId() { return seatId; }

    public Long getMemberId() { return memberId; }

    public Long getSequenceNumber() { return sequenceNumber; }

    public OfferStatus getStatus() { return status; }

    public String getOfferToken() { return offerToken; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
}
