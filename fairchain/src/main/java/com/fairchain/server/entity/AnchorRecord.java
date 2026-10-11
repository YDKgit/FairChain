package com.fairchain.server.entity;
import jakarta.persistence.*;
@Entity @Table(name="AnchorRecord", uniqueConstraints=@UniqueConstraint(name="uk_anchor_client_ticket_version", columnNames={"client_server_id","recordKey","version"})) public class AnchorRecord {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="id")
 public Long id;
 @Column(name="client_server_id")
 public String clientServerId;
 @Column(name="event_id")
 public Long eventId;
 @Column(name="userKey",length=64)
 public String userKey;
 @Column(name="recordKey",length=64)
 public String recordKey;
 @Column(name="version")
 public Long version;
 @Column(name="recordHash",length=64)
 public String recordHash;
 @Column(name="type")
 @Enumerated(EnumType.STRING)
 public com.fairchain.api.dto.RecordType type;
 @Column(name="blockState")
 @Enumerated(EnumType.STRING)
 public com.fairchain.api.dto.BlockState blockState;
 @Column(name="blockIndex")
 public Long blockIndex;
 @Column(name="createdAt")
 public java.time.LocalDateTime createdAt;
}
