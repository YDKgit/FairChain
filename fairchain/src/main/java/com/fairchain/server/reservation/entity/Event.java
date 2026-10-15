package com.fairchain.server.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 공연 (= 예제서버 concerts.id). 좌석 원본은 예제서버에 있고 FairChain은 숫자만 관리 */
@Entity
@Table(name = "event")
public class Event {

    @Id
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "client_server_id", nullable = false)
    private String clientId;

    @Column(name = "total_seats", nullable = false)
    private Long totalSeats = 0L;

    @Column(name = "remaining_seats", nullable = false)
    private Long remainingSeats = 0L;

    /** 처음 매진된 시각 — 한 번 기록되면 유지 (매진 전/후 취소 구분) */
    @Column(name = "sold_out_at")
    private LocalDateTime soldOutAt;

    protected Event() {}

    public Event(Long eventId, String clientId) {
        this.eventId = eventId;
        this.clientId = clientId;
    }

    /** 발행(ISSUE) */
    public void issueSeat() {
        totalSeats++;
        remainingSeats++;
    }

    /** 판매(PURCHASE) — 이번 판매로 처음 매진되면 true */
    public boolean sell() {
        remainingSeats = Math.max(0L, remainingSeats - 1);
        if (remainingSeats == 0 && soldOutAt == null) {
            soldOutAt = LocalDateTime.now();
            return true;
        }
        return false;
    }

    /** 취소(CANCEL) */
    public void cancel() {
        remainingSeats = Math.min(totalSeats, remainingSeats + 1);
    }

    public boolean isSoldOut() {
        return soldOutAt != null;
    }

    public Long getEventId() {
        return eventId;
    }

    public String getClientId() {
        return clientId;
    }

    public Long getTotalSeats() {
        return totalSeats;
    }

    public Long getRemainingSeats() {
        return remainingSeats;
    }

    public LocalDateTime getSoldOutAt() {
        return soldOutAt;
    }
}
