package com.fairchain.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 주문 (설계 클래스 없음, ERD orders) — 예매 트랜잭션의 모의 결제 기록. order는 SQL 예약어라 테이블명 지정 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long orderId;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false, unique = true)
    private Long ticketId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount;

    @Column(nullable = false)
    private LocalDateTime purchasedAt;

    protected Order() {}

    public Order(Long memberId, Long ticketId, BigDecimal paidAmount, LocalDateTime purchasedAt) {
        this.memberId = memberId;
        this.ticketId = ticketId;
        this.paidAmount = paidAmount;
        this.purchasedAt = purchasedAt;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public LocalDateTime getPurchasedAt() {
        return purchasedAt;
    }
}
