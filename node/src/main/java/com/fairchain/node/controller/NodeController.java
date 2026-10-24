package com.fairchain.node.controller;
import com.fairchain.node.service.NodeService;
import com.fairchain.nodeapi.*;
import com.fairchain.nodeapi.dto.*;
import org.springframework.web.bind.annotation.*;
/** 경로 · 매핑은 node-api 인터페이스에 있고, 여기서는 implements만 한다. /node/** 인증은 NodeAuthFilter */
@RestController public class NodeController implements BlockRecordApi,NodeConsensusApi,NodeVerificationApi,NodeAuditApi {
 private final NodeService service;
 public NodeController(NodeService service){this.service=service;}
 @Override public boolean acceptDecision(Long blockIndex,String blockHash){return service.recordCommitDecision(blockIndex,blockHash);}
 /** Restricted manual retry; protected by the same node-to-node shared secret filter. */
 @Override public BlockResultDto retryPrepared(Long blockIndex,String blockHash){return service.retryPrepared(blockIndex,blockHash);}
 @Override public BlockProofDto proof(String recordKey){return service.proof(recordKey);}
 @Override public NodeLedgerStatusDto ledgerStatus(){return service.ledgerStatus();}
 @Override public java.util.List<BlockDto> ledgerBlocks(Long fromIndex,int limit){return service.ledgerBlocks(fromIndex,limit);}
 @Override public BlockResultDto submitRecord(BlockRecordDto dto){return service.submit(dto);}
 @Override public BlockVerifyResultDto verifyRecord(BlockVerifyDto dto){return service.verify(dto);}
 @Override public BlockBatchVerifyResponseDto verifyBatch(BlockBatchVerifyRequestDto dto){return service.batch(dto);}
 @Override public boolean receiveProposal(BlockProposalDto dto){return service.propose(dto);}
 @Override public boolean confirmBlock(Long blockIndex,String blockHash){return service.confirm(blockIndex,blockHash);}
 @Override public java.util.List<BlockProposalDto> blocks(Long fromIndex,int limit){return service.blocks(fromIndex,limit);}
}
