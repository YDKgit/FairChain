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

/**
 * 재배정 회차 안에서 명단의 한 사람에게 주는 제안.
 * "이 공연의 남은 취소표 중 하나를 기한 안에 고르세요" — 고른 좌석은 seatId에 남는다.
 */
@Entity
@Table(name = "reallocation_offer")
public class ReallocationOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roundId;

    @Column(nullable = false)
    private Long concertId;

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

    private LocalDateTime offeredAt;

    private LocalDateTime expiresAt;

    /** 수락해서 예매한 좌석 */
    private Long seatId;

    protected ReallocationOffer() {}

    public ReallocationOffer(Long roundId, Long concertId, Long memberId, Long sequenceNumber) {
        this.roundId = roundId;
        this.concertId = concertId;
        this.memberId = memberId;
        this.sequenceNumber = sequenceNumber;
        this.status = OfferStatus.WAITING;
    }

    public void offer(Duration ttl) {
        status = OfferStatus.OFFERED;
        offerToken = UUID.randomUUID().toString();
        offeredAt = LocalDateTime.now();
        expiresAt = offeredAt.plus(ttl);
    }

    public void accept(Long seatId) {
        status = OfferStatus.ACCEPTED;
        this.seatId = seatId;
    }

    public void expire() {
        status = OfferStatus.EXPIRED;
    }

    /** 회차가 끝나 차례가 오지 않은 사람 */
    public void skip() {
        status = OfferStatus.SKIPPED;
    }

    public boolean isUsableBy(Long memberId, LocalDateTime now) {
        return status == OfferStatus.OFFERED && this.memberId.equals(memberId) && now.isBefore(expiresAt);
    }

    public Long getId() { return id; }

    public Long getRoundId() { return roundId; }

    public Long getConcertId() { return concertId; }

    public Long getMemberId() { return memberId; }

    public Long getSequenceNumber() { return sequenceNumber; }

    public OfferStatus getStatus() { return status; }

    public String getOfferToken() { return offerToken; }

    public LocalDateTime getOfferedAt() { return offeredAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }

    public Long getSeatId() { return seatId; }
}
