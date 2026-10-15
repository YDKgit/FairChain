package com.fairchain.example.concert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 콘서트. id가 FairChain의 eventId와 같다 */
@Entity
@Table(name = "concert")
public class Concert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    /** 예매 오픈 시각 — 이 전에는 대기열에 들어갈 수 없다 */
    @Column(nullable = false)
    private LocalDateTime eventOpen;

    @Column(nullable = false)
    private LocalDateTime eventStart;

    @Column(nullable = false)
    private LocalDateTime eventEnd;

    @Column(nullable = false)
    private String venue;

    protected Concert() {}

    public Concert(String title, LocalDateTime eventOpen, LocalDateTime eventStart, LocalDateTime eventEnd, String venue) {
        update(title, eventOpen, eventStart, eventEnd, venue);
    }

    public void update(String title, LocalDateTime eventOpen, LocalDateTime eventStart, LocalDateTime eventEnd, String venue) {
        this.title = title;
        this.eventOpen = eventOpen;
        this.eventStart = eventStart;
        this.eventEnd = eventEnd;
        this.venue = venue;
    }

    public boolean isOpen(LocalDateTime now) {
        return !now.isBefore(eventOpen);
    }

    /** 입장 가능 시간: 공연 시작 3시간 전부터 종료까지 */
    public boolean isEntryTime(LocalDateTime now) {
        return !now.isBefore(eventStart.minusHours(3)) && !now.isAfter(eventEnd);
    }

    public Long getId() { return id; }

    public String getTitle() { return title; }

    public LocalDateTime getEventOpen() { return eventOpen; }

    public LocalDateTime getEventStart() { return eventStart; }

    public LocalDateTime getEventEnd() { return eventEnd; }

    public String getVenue() { return venue; }
}
