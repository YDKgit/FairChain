package com.fairchain.server.entity;
import jakarta.persistence.*;
@Entity @Table(name="AdmissionPass") public class AdmissionPass {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="id")
 public Long id;
 @Column(name="user_id")
 public Long userId;
 @Column(name="event_id")
 public Long eventId;
 @Column(name="issuedAt")
 public java.time.LocalDateTime issuedAt;
 @Column(name="expiresAt")
 public java.time.LocalDateTime expiresAt;
}
