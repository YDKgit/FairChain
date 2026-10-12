package com.fairchain.node.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity @Table(name="Block")
public class Block {
 @Id @Column(name="blockIndex") public Long blockIndex;
 @Column(nullable=false,length=64) public String prevBlockHash;
 @Column(nullable=false,length=64) public String recordKey;
 @Column(nullable=false) public Long version;
 @Column(nullable=false,length=64) public String recordHash;
 @Column(nullable=false) public LocalDateTime timestamp;
 @Column(nullable=false,length=64) public String blockHash;
 protected Block() {}
 public Block(Long index,String prev,String key,Long version,String hash,LocalDateTime time,String blockHash) {
 this.blockIndex=index;this.prevBlockHash=prev;this.recordKey=key;this.version=version;this.recordHash=hash;this.timestamp=time;this.blockHash=blockHash;
 }
}
