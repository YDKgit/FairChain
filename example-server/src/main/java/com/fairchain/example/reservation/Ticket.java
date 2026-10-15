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
import java.util.UUID;

/** 티켓(바우처). version은 이 티켓 상태를 기록한 FairChain 기록 버전 */
@Entity
@Table(name = "ticket")
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
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    /** 입장용 QR 토큰 */
    @Column(nullable = false, unique = true, length = 36)
    private String qrToken;

    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private LocalDateTime issuedAt;

    private LocalDateTime checkedInAt;

    protected Ticket() {}

    public static Ticket issue(Long memberId, Seat seat, long version) {
        Ticket t = new Ticket();
        t.memberId = memberId;
        t.seatId = seat.getId();
        t.concertId = seat.getConcertId();
        t.status = TicketStatus.ACTIVE;
        t.qrToken = UUID.randomUUID().toString();
        t.version = version;
        t.issuedAt = LocalDateTime.now();
        return t;
    }

    public void cancel(long version) {
        status = TicketStatus.CANCELLED;
        this.version = version;
    }

    public void checkIn(long version) {
        status = TicketStatus.CHECKED_IN;
        checkedInAt = LocalDateTime.now();
        this.version = version;
    }

    public Long getTicketId() { return ticketId; }

    public Long getMemberId() { return memberId; }

    public Long getSeatId() { return seatId; }

    public Long getConcertId() { return concertId; }

    public TicketStatus getStatus() { return status; }

    public String getQrToken() { return qrToken; }

    public Long getVersion() { return version; }

    public LocalDateTime getIssuedAt() { return issuedAt; }

    public LocalDateTime getCheckedInAt() { return checkedInAt; }
}
