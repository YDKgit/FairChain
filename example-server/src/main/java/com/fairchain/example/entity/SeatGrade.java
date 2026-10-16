package com.fairchain.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** C-0710 좌석 등급 */
@Entity
@Table(name = "seat_grades")
public class SeatGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long concertId;

    @Column(nullable = false, length = 50)
    private String gradeName;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    protected SeatGrade() {}

    public SeatGrade(Long concertId, String gradeName, BigDecimal price) {
        this.concertId = concertId;
        this.gradeName = gradeName;
        this.price = price;
    }

    public Long getId() {
        return id;
    }

    public Long getConcertId() {
        return concertId;
    }

    public String getGradeName() {
        return gradeName;
    }

    public BigDecimal getPrice() {
        return price;
    }
}
