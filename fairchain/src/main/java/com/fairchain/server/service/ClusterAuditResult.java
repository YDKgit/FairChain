package com.fairchain.server.service;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import java.util.List;
public record ClusterAuditResult(boolean consistent, String reason, List<NodeObservation> nodes) {
 public record NodeObservation(String url, boolean reachable, NodeLedgerStatusDto status, String error) {}
}
