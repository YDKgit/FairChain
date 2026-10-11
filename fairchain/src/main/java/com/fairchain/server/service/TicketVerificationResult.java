package com.fairchain.server.service;
/** Only VERIFIED means that both FairChain and the blockchain agree. */
public record TicketVerificationResult(boolean valid, String reason, Long blockIndex) {}
