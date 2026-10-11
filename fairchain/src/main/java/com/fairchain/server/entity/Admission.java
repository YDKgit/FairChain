package com.fairchain.server.entity;
import jakarta.persistence.*;
@Entity @Table(name="Admission") public class Admission {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="id")
 public Long id;
 @Column(name="user_id")
 public Long userId;
 @Column(name="event_id")
 public Long eventId;
 @Column(name="sequenceNumber")
 public Long sequenceNumber;
 @Column(name="status")
 public String status;
 @Column(name="admittedAt")
 public java.time.LocalDateTime admittedAt;
}
