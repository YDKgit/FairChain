package com.fairchain.node.controller;
import com.fairchain.node.service.NodeService;
import com.fairchain.nodeapi.*;
import com.fairchain.nodeapi.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController public class NodeController implements BlockRecordApi,NodeConsensusApi,NodeVerificationApi {
 private final NodeService service;private final String secret;
 public NodeController(NodeService service,@Value("${node.secret}") String secret){this.service=service;this.secret=secret;}
 @PostMapping("/node/consensus/blocks/{blockIndex}/decision")
 public boolean acceptDecision(@PathVariable Long blockIndex,@RequestParam String blockHash) {
  return service.recordCommitDecision(blockIndex,blockHash);
 }
 /** Restricted manual retry; protected by the same node-to-node shared secret filter. */
 @PostMapping("/node/consensus/recovery/{blockIndex}")
 public BlockResultDto retryPrepared(@PathVariable Long blockIndex,@RequestParam String blockHash){
  return service.retryPrepared(blockIndex,blockHash);
 }
 @GetMapping("/node/records/proof/{recordKey}")
 public BlockProofDto proof(@PathVariable String recordKey){return service.proof(recordKey);}
 @GetMapping("/node/ledger/status") public NodeLedgerStatusDto ledgerStatus(){return service.ledgerStatus();}
 @Override public BlockResultDto submitRecord(BlockRecordDto dto){return service.submit(dto);}
 @Override public BlockVerifyResultDto verifyRecord(BlockVerifyDto dto){return service.verify(dto);}
 @Override public BlockBatchVerifyResponseDto verifyBatch(BlockBatchVerifyRequestDto dto){return service.batch(dto);}
 @Override public boolean receiveProposal(BlockProposalDto dto){return service.propose(dto);}
 @Override public boolean confirmBlock(Long blockIndex,String blockHash){return service.confirm(blockIndex,blockHash);}
}
