package com.fairchain.server.service;
/** Fail-closed ticket status. Boolean valid means admission is currently allowed. */
public record TicketLifecycleResult(boolean valid, String reason, String state,
 Long version, Long blockIndex) {}
