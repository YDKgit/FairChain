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

/** C-0316 티켓 — version은 항상 Seat.recordVersion과 맞춘다 */
@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long ticketId;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long seatId;

    @Column(nullable = false)
    private Long concertId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    @Column(nullable = false, unique = true)
    private String qrToken;

    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private LocalDateTime issuedAt;

    private LocalDateTime checkedInAt;

    protected Ticket() {}

    /** 발급 — ACTIVE */
    public Ticket(Long memberId, Long seatId, Long concertId, String qrToken, Long version, LocalDateTime issuedAt) {
        this.memberId = memberId;
        this.seatId = seatId;
        this.concertId = concertId;
        this.status = TicketStatus.ACTIVE;
        this.qrToken = qrToken;
        this.version = version;
        this.issuedAt = issuedAt;
    }

    public void cancel(long newVersion) {
        this.status = TicketStatus.CANCELLED;
        this.version = newVersion;
    }

    public void checkIn(LocalDateTime now, long newVersion) {
        this.status = TicketStatus.CHECKED_IN;
        this.checkedInAt = now;
        this.version = newVersion;
    }

    public boolean isOwnedBy(Long memberId) {
        return this.memberId.equals(memberId);
    }

    public Long getTicketId() {
        return ticketId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Long getSeatId() {
        return seatId;
    }

    public Long getConcertId() {
        return concertId;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public String getQrToken() {
        return qrToken;
    }

    public Long getVersion() {
        return version;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public LocalDateTime getCheckedInAt() {
        return checkedInAt;
    }
}
