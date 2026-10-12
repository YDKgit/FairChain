package com.fairchain.nodeapi.dto;
/** Proof of a committed record from one node. Valid only when chain integrity is true. */
public record BlockProofDto(boolean valid, Long blockIndex, Long version, String recordHash, String blockHash) {}
