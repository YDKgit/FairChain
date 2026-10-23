package com.fairchain.example.concert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "seat_grade")
public class SeatGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long concertId;

    @Column(nullable = false, length = 20)
    private String gradeName;

    @Column(nullable = false)
    private BigDecimal price;

    protected SeatGrade() {}

    public SeatGrade(Long concertId, String gradeName, BigDecimal price) {
        this.concertId = concertId;
        this.gradeName = gradeName;
        this.price = price;
    }

    public Long getId() { return id; }

    public Long getConcertId() { return concertId; }

    public String getGradeName() { return gradeName; }

    public BigDecimal getPrice() { return price; }
}
