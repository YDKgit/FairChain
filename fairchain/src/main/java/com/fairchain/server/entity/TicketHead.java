package com.fairchain.server.entity;
import jakarta.persistence.*;
/** One row per client ticket. Database row lock serializes state transitions across FairChain replicas. */
@Entity @Table(name="ticket_heads",uniqueConstraints=@UniqueConstraint(name="uk_ticket_head",columnNames={"client_id","record_key"}))
public class TicketHead {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(name="client_id",nullable=false,length=128) public String clientId;
 @Column(name="record_key",nullable=false,length=64) public String recordKey;
 protected TicketHead(){}
 public TicketHead(String clientId,String recordKey){this.clientId=clientId;this.recordKey=recordKey;}
}
