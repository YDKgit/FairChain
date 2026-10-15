package com.fairchain.nodeapi.dto;
import java.util.List;
/** Diagnostic snapshot; does not by itself prove distributed consensus. */
public record NodeLedgerStatusDto(Long height, String headHash, boolean chainValid,
                                  List<Long> pendingIndices) {}
