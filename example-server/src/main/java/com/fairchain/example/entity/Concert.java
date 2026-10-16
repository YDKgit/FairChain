package com.fairchain.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** C-0709 콘서트 — id가 FairChain eventId */
@Entity
@Table(name = "concerts")
public class Concert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    /** 예매 오픈 시각 */
    @Column(nullable = false)
    private LocalDateTime eventOpen;

    @Column(nullable = false)
    private LocalDateTime eventStart;

    @Column(nullable = false)
    private LocalDateTime eventEnd;

    @Column(nullable = false, length = 200)
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

    public boolean isOpened(LocalDateTime now) {
        return !now.isBefore(eventOpen);
    }

    /** 공연 기간(입장 가능 시간) 안인지 */
    public boolean isInEventPeriod(LocalDateTime now) {
        return !now.isBefore(eventStart) && !now.isAfter(eventEnd);
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public LocalDateTime getEventOpen() {
        return eventOpen;
    }

    public LocalDateTime getEventStart() {
        return eventStart;
    }

    public LocalDateTime getEventEnd() {
        return eventEnd;
    }

    public String getVenue() {
        return venue;
    }
}
