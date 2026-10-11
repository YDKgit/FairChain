package com.fairchain.server.entity;
import jakarta.persistence.*;
@Entity @Table(name="Event") public class Event {
 @Id @Column(name="event_id")
 public Long eventId;
 @Column(name="client_server_id")
 public String clientServerId;
 @Column(name="valid_at")
 public java.time.LocalDateTime validAt;
 @Column(name="valid_to")
 public java.time.LocalDateTime validTo;
 @Column(name="totalSeats")
 public Long totalSeats;
 @Column(name="remainingSeats")
 public Long remainingSeats;
 @Column(name="soldOutAt")
 public java.time.LocalDateTime soldOutAt;
}
