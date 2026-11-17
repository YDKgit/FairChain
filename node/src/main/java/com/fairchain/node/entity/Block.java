package com.fairchain.node.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
/**
 * 확정 블록. blockHash = SHA-256(index ‖ prevHash ‖ recordKey ‖ version ‖ recordHash) — 계산식은 바꾸지 않는다.
 * 서명은 블록 옆 칸(signerId · signature)에 따로 둔다 (미정사항 8번): 블록과 한 행이라 블록만 있고 서명이 없는 상태가 생기지 않는다.
 */
@Entity @Table(name="Block")
public class Block {
 @Id @Column(name="blockIndex") public Long blockIndex;
 @Column(nullable=false,length=64) public String prevBlockHash;
 @Column(nullable=false,length=64) public String recordKey;
 @Column(nullable=false) public Long version;
 @Column(nullable=false,length=64) public String recordHash;
 @Column(nullable=false) public LocalDateTime timestamp;
 @Column(nullable=false,length=64) public String blockHash;
 /** 이 사본에 서명한 노드 (리더 층 노드는 자기 자신) */
 @Column(length=32) public String signerId;
 /** signerId의 Ed25519 개인키로 blockHash에 한 서명 (Base64) */
 @Column(length=128) public String signature;
 protected Block() {}
 public Block(Long index,String prev,String key,Long version,String hash,LocalDateTime time,String blockHash,String signerId,String signature) {
 this.blockIndex=index;this.prevBlockHash=prev;this.recordKey=key;this.version=version;this.recordHash=hash;this.timestamp=time;this.blockHash=blockHash;
 this.signerId=signerId;this.signature=signature;
 }
}
