package com.fairchain.node.entity;
import jakarta.persistence.*;
/** Persisted PREPARE vote so a restart cannot silently lose an accepted proposal. */
@Entity @Table(name="pending_blocks")
public class PendingBlock {
 @Id public Long blockIndex;
 @Column(nullable=false,length=64) public String prevBlockHash;
 @Column(nullable=false,length=64) public String recordKey;
 @Column(nullable=false) public Long version;
 @Column(nullable=false,length=64) public String recordHash;
 @Column(nullable=false) public Long timestamp;
 @Column(nullable=false,length=64) public String blockHash;
 @Column(nullable=false) public boolean commitDecided = false;
 protected PendingBlock() {}
 public PendingBlock(Long index,String prev,String key,Long version,String recordHash,Long timestamp,String blockHash){
  this.blockIndex=index;this.prevBlockHash=prev;this.recordKey=key;this.version=version;
  this.recordHash=recordHash;this.timestamp=timestamp;this.blockHash=blockHash;
 }
}
