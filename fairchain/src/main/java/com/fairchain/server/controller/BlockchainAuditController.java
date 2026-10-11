package com.fairchain.server.controller;
import com.fairchain.server.service.ClusterAuditResult;
import com.fairchain.server.service.NodeClient;
import com.fairchain.server.repository.ClientsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
/** Existing registered client API key is required; do not expose ledger diagnostics publicly. */
@RestController
@RequestMapping("/api/blockchain")
public class BlockchainAuditController {
 private final NodeClient client;
 private final ClientsRepository clients;
 public BlockchainAuditController(NodeClient client, ClientsRepository clients) {this.client=client;this.clients=clients;}
 @GetMapping("/audit")
 public ClusterAuditResult audit(@RequestHeader("X-API-Key") String key) {
  if(key==null || clients.findAll().stream().noneMatch(c->key.equals(c.apiKey)))
   throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid API key");
  return client.auditCluster();
 }
}
