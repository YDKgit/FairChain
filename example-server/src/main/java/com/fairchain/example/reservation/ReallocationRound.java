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

/**
 * 취소표 재배정 회차. 매일 제안 시각에 그때까지 모인 취소표를 묶어 한 회차를 열고,
 * 명단 순번대로 한 사람씩 "남은 취소표 중 하나를 고르세요"라고 제안한다.
 * 명단을 다 돌고도 취소표가 남으면 다음 날 일반 판매 시각에 푼다.
 */
@Entity
@Table(name = "reallocation_round")
public class ReallocationRound {

    public enum Status {
        /** 명단에 순번대로 제안 중 */
        OFFERING,
        /** 명단을 다 돌았고 남은 취소표를 releaseAt에 일반 판매로 푼다 */
        RELEASE_PENDING,
        /** 끝남 (취소표가 모두 나갔거나 일반 판매로 풀림) */
        DONE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long concertId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    /** 명단에 넣은 사람 수 */
    @Column(nullable = false)
    private int candidateCount;

    /** 이 회차에 묶인 취소표 수 */
    @Column(nullable = false)
    private int seatCount;

    private LocalDateTime releaseAt;

    private LocalDateTime finishedAt;

    protected ReallocationRound() {}

    public ReallocationRound(Long concertId, int candidateCount, int seatCount) {
        this.concertId = concertId;
        this.status = Status.OFFERING;
        this.startedAt = LocalDateTime.now();
        this.candidateCount = candidateCount;
        this.seatCount = seatCount;
    }

    public void scheduleRelease(LocalDateTime releaseAt) {
        this.status = Status.RELEASE_PENDING;
        this.releaseAt = releaseAt;
    }

    public void finish() {
        this.status = Status.DONE;
        this.finishedAt = LocalDateTime.now();
    }

    public boolean isOpen() {
        return status != Status.DONE;
    }

    public Long getId() { return id; }

    public Long getConcertId() { return concertId; }

    public Status getStatus() { return status; }

    public LocalDateTime getStartedAt() { return startedAt; }

    public int getCandidateCount() { return candidateCount; }

    public int getSeatCount() { return seatCount; }

    public LocalDateTime getReleaseAt() { return releaseAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
}
