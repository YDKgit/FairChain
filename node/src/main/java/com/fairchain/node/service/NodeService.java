package com.fairchain.node.service;
import com.fairchain.node.entity.Block;
import com.fairchain.node.entity.PendingBlock;
import com.fairchain.node.repository.BlockRepository;
import com.fairchain.node.repository.PendingBlockRepository;
import com.fairchain.nodeapi.dto.*;
import com.fairchain.api.util.Sha256RecordHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;
@Service public class NodeService {
 private static final String GENESIS="0".repeat(64);
 private static final Pattern HEX=Pattern.compile("[0-9a-f]{64}");
 private final BlockRepository repo; private final PendingBlockRepository pendingRepo;

 private final RestClient http=RestClient.builder().requestFactory(requestFactory()).build();
 private static org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory() {
  var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
  factory.setConnectTimeout(2000);factory.setReadTimeout(5000);return factory;
 }
 private final Sha256RecordHasher hasher=new Sha256RecordHasher();
 private final List<String> peers; private final String secret;

 public NodeService(BlockRepository repo,PendingBlockRepository pendingRepo,@Value("${node.peers:}") String peers,@Value("${node.secret}") String secret) {
 this.repo=repo;this.pendingRepo=pendingRepo;this.peers=Arrays.stream(peers.split(",")).map(String::trim).filter(s->!s.isBlank()).toList();this.secret=secret;
 if(this.peers.size()!=2) throw new IllegalStateException("Exactly two peers required for three-node demo");
 }
 private String hash(BlockProposalDto p) { return hasher.hash(p.blockIndex().toString(),p.prevBlockHash(),p.recordKey(),p.version().toString(),p.recordHash(),p.timestamp().toString()); }
 private long next() { return repo.findTopByOrderByBlockIndexDesc().map(b->b.blockIndex+1).orElse(1L); }
 private String prev() { return repo.findTopByOrderByBlockIndexDesc().map(b->b.blockHash).orElse(GENESIS); }
 private void validateKey(String key,String hash,Long version) {
 if(key==null || !HEX.matcher(key).matches() || hash==null || !HEX.matcher(hash).matches() || version==null || version<1) throw new IllegalArgumentException("Invalid record key/hash/version");
 }
 private boolean validProposal(BlockProposalDto p) {
 if(p==null || p.blockIndex()==null || p.version()==null || p.timestamp()==null || p.blockHash()==null) return false;
 try { validateKey(p.recordKey(),p.recordHash(),p.version()); } catch(IllegalArgumentException ex) {return false;}
 return p.blockIndex().equals(next()) && prev().equals(p.prevBlockHash()) && hash(p).equals(p.blockHash()) &&
 repo.findTopByRecordKeyOrderByBlockIndexDesc(p.recordKey()).map(b->p.version()>b.version).orElse(true);
 }
 private BlockProposalDto toProposal(PendingBlock p) {return new BlockProposalDto(p.blockIndex,p.prevBlockHash,p.recordKey,p.version,p.recordHash,p.timestamp,p.blockHash);}
 private PendingBlock toPending(BlockProposalDto p) {return new PendingBlock(p.blockIndex(),p.prevBlockHash(),p.recordKey(),p.version(),p.recordHash(),p.timestamp(),p.blockHash());}
 private Block toEntity(BlockProposalDto p) {return new Block(p.blockIndex(),p.prevBlockHash(),p.recordKey(),p.version(),p.recordHash(),LocalDateTime.ofInstant(Instant.ofEpochMilli(p.timestamp()),ZoneOffset.UTC),p.blockHash());}
 private boolean peerPost(String peer,String path,Object body,Class<Boolean> result) {
 try { var request=http.post().uri(peer+path).header("X-Node-Secret",secret);
 return Boolean.TRUE.equals((body==null ? request : request.body(body)).retrieve().body(result)); }
 catch(Exception e) {return false;}
 }
 public synchronized BlockResultDto submit(BlockRecordDto dto) {
 if(dto==null) throw new IllegalArgumentException("Missing record");
 // Refuse new writes until previous prepared proposals have been resolved.
 if(pendingRepo.count()>0) return new BlockResultDto(false,null);
 validateKey(dto.recordKey(),dto.recordHash(),dto.version());
 var existing=repo.findTopByRecordKeyOrderByBlockIndexDesc(dto.recordKey());
 if(existing.isPresent() && existing.get().version.equals(dto.version()) && existing.get().recordHash.equals(dto.recordHash())) return new BlockResultDto(true,existing.get().blockIndex);
 if(existing.isPresent() && dto.version()<=existing.get().version) return new BlockResultDto(false,null);
 long time=System.currentTimeMillis();
 var unsigned=new BlockProposalDto(next(),prev(),dto.recordKey(),dto.version(),dto.recordHash(),time,"");
 var prop=new BlockProposalDto(unsigned.blockIndex(),unsigned.prevBlockHash(),unsigned.recordKey(),unsigned.version(),unsigned.recordHash(),time,hash(unsigned));
 pendingRepo.saveAndFlush(toPending(prop));
 int votes=1;for(String peer:peers) if(peerPost(peer,"/node/consensus/proposals",prop,Boolean.class)) votes++;
 if(votes!=3) return new BlockResultDto(false,null); // strict 3/3 PREPARE
 // Persist a durable decision BEFORE notifying peers. If the process crashes,
 // recovery can safely redeliver precisely this decision, not invent a new block.
 if(!recordCommitDecision(prop.blockIndex(), prop.blockHash())) return new BlockResultDto(false,null);
 // Network acknowledgements are not atomic; recover will retry this decision.
 // Broadcast persisted COMMIT intent before asking each peer to apply it.
 for(String peer:peers) if(!peerPost(peer,"/node/consensus/blocks/"+prop.blockIndex()+"/decision?blockHash="+prop.blockHash(),null,Boolean.class))
  return new BlockResultDto(false,null);
 int confirmations=1;for(String peer:peers) if(peerPost(peer,"/node/consensus/blocks/"+prop.blockIndex()+"/confirm?blockHash="+prop.blockHash(),null,Boolean.class)) confirmations++;
 if(confirmations!=3) return new BlockResultDto(false,null);
 if(!confirm(prop.blockIndex(),prop.blockHash())) return new BlockResultDto(false,null);
 return new BlockResultDto(true,prop.blockIndex());
 }
 /**
  * Operator-triggered retry of an already persisted proposal. No new block or hash is generated.
  * Peers that have already committed the identical hash answer true to confirm (idempotent).
  * A conflicting or unavailable peer makes this operation fail closed; pending data is retained.
  * This is best-effort convergence, NOT atomic commit or automated distributed recovery.
  */
 public synchronized BlockResultDto retryPrepared(Long index, String expectedHash) {
  if(index==null || expectedHash==null || !HEX.matcher(expectedHash).matches())
   return new BlockResultDto(false,null);
  var saved=pendingRepo.findById(index);
  if(saved.isEmpty()) return new BlockResultDto(false,null);
  var proposal=toProposal(saved.get());
  if(!proposal.blockHash().equals(expectedHash) || !validProposal(proposal))
   return new BlockResultDto(false,null);
  // Recovery must NEVER create a commit decision from an undecided PREPARE.
  if(!saved.get().commitDecided) return new BlockResultDto(false,null);
  // Re-propose to peers that may have missed the initial PREPARE. A peer which
  // already committed will reject PREPARE, but its idempotent CONFIRM can succeed.
  for(String peer:peers) {
   peerPost(peer,"/node/consensus/proposals",proposal,Boolean.class);
   if(!peerPost(peer,"/node/consensus/blocks/"+index+"/decision?blockHash="+expectedHash,null,Boolean.class))
    return new BlockResultDto(false,null);
   if(!peerPost(peer,"/node/consensus/blocks/"+index+"/confirm?blockHash="+expectedHash,null,Boolean.class))
    return new BlockResultDto(false,null);
  }
  if(!confirm(index,expectedHash)) return new BlockResultDto(false,null);
  return new BlockResultDto(true,index);
 }
 public synchronized boolean propose(BlockProposalDto p) {
 if(p == null || p.blockIndex() == null || p.blockHash() == null) return false;
 // Idempotent replay after a successful COMMIT. Never accept a conflicting hash.
 var committed = repo.findById(p.blockIndex());
 if(committed.isPresent()) return p.blockHash().equals(committed.get().blockHash) &&
     Objects.equals(p.recordKey(), committed.get().recordKey) &&
     Objects.equals(p.version(), committed.get().version) &&
     Objects.equals(p.recordHash(), committed.get().recordHash) &&
     Objects.equals(p.prevBlockHash(), committed.get().prevBlockHash) &&
     Objects.equals(p.timestamp(), committed.get().timestamp.toInstant(ZoneOffset.UTC).toEpochMilli());
 if(!validProposal(p)) return false;
 var old=pendingRepo.findById(p.blockIndex());
 if(old.isPresent()) return toProposal(old.get()).equals(p);
 // Prevent a competing proposal while a previous proposal remains unresolved.
 if(pendingRepo.count()>0) return false;
 pendingRepo.saveAndFlush(toPending(p));
 return true;
 }
 @Transactional public synchronized boolean confirm(Long index,String expectedHash) {
 if(index==null || expectedHash==null || !HEX.matcher(expectedHash).matches()) return false;
 var committed=repo.findById(index);
 if(committed.isPresent()) return expectedHash.equals(committed.get().blockHash);
 var prepared=pendingRepo.findById(index);
 if(prepared.isEmpty()) return false;
 var p=toProposal(prepared.get());
 if(!expectedHash.equals(p.blockHash()) || !prepared.get().commitDecided || !validProposal(p)) return false;
 repo.saveAndFlush(toEntity(p));pendingRepo.delete(prepared.get());return true;
 }
 /** Durable decision, committed in a DB transaction before peer RPCs.
  * Repository.saveAndFlush is transactional; no surrounding submit transaction exists.
  */
 public boolean recordCommitDecision(Long index,String expectedHash) {
  if(index==null || expectedHash==null) return false;
  var item=pendingRepo.findById(index);
  if(item.isEmpty() || !expectedHash.equals(item.get().blockHash)) return false;
  if(!validProposal(toProposal(item.get()))) return false;
  if(!item.get().commitDecided) {
   item.get().commitDecided=true;
   pendingRepo.saveAndFlush(item.get());
  }
  return true;
 }
 /** Verify the entire history up to this block; checking only the last link misses older tampering. */
 private boolean integrity(Block target) {
  if(target == null || target.blockIndex == null || target.blockIndex < 1) return false;
  // Verification must cover the current HEAD as well: later tampering invalidates the ledger.
  var head = repo.findTopByOrderByBlockIndexDesc();
  if(head.isEmpty() || head.get().blockIndex == null || head.get().blockIndex < target.blockIndex) return false;
  String expectedPrevious = GENESIS;
  for(long i=1; i<=head.get().blockIndex; i++) {
   var found=repo.findById(i);
   if(found.isEmpty()) return false;
   Block b=found.get();
   if(b.blockIndex == null || b.blockIndex != i || b.timestamp == null ||
      b.prevBlockHash == null || b.blockHash == null || b.version == null ||
      b.recordKey == null || b.recordHash == null) return false;
   if(!expectedPrevious.equals(b.prevBlockHash)) return false;
   var proposal=new BlockProposalDto(b.blockIndex,b.prevBlockHash,b.recordKey,b.version,
     b.recordHash,b.timestamp.toInstant(ZoneOffset.UTC).toEpochMilli(),b.blockHash);
   if(!hash(proposal).equals(b.blockHash)) return false;
   try { validateKey(b.recordKey,b.recordHash,b.version); } catch(IllegalArgumentException ex) {return false;}
   expectedPrevious=b.blockHash;
  }
  return true;
 }
 /** Reads the full chain and reports any unresolved prepared proposals. */
 public NodeLedgerStatusDto ledgerStatus() {
  var head=repo.findTopByOrderByBlockIndexDesc();
  var prepared=pendingRepo.findAll().stream().map(x->x.blockIndex).sorted().toList();
  if(head.isEmpty()) return new NodeLedgerStatusDto(0L,GENESIS,true,prepared);
  Block b=head.get();
  return new NodeLedgerStatusDto(b.blockIndex,b.blockHash,integrity(b),prepared);
 }
 public BlockProofDto proof(String recordKey) {
  if(recordKey==null || !HEX.matcher(recordKey).matches()) return new BlockProofDto(false,null,null,null,null);
  return repo.findTopByRecordKeyOrderByBlockIndexDesc(recordKey)
    .map(b->new BlockProofDto(integrity(b),b.blockIndex,b.version,b.recordHash,b.blockHash))
    .orElse(new BlockProofDto(false,null,null,null,null));
 }
 public BlockVerifyResultDto verify(BlockVerifyDto dto) {
  if(dto==null || dto.recordKey()==null || dto.version()==null || dto.recordHash()==null ||
     !HEX.matcher(dto.recordKey()).matches() || !HEX.matcher(dto.recordHash()).matches() || dto.version()<1)
   return new BlockVerifyResultDto(false,null,null);
  var b=repo.findTopByRecordKeyOrderByBlockIndexDesc(dto.recordKey());
  return b.map(x->new BlockVerifyResultDto(integrity(x)&&x.version.equals(dto.version())&&
    x.recordHash.equals(dto.recordHash()),x.version,x.recordHash))
    .orElse(new BlockVerifyResultDto(false,null,null));
 }
 public BlockBatchVerifyResponseDto batch(BlockBatchVerifyRequestDto dto) {
  if(dto==null || dto.records()==null) throw new IllegalArgumentException("Missing records");
  return new BlockBatchVerifyResponseDto(dto.records().stream().map(r->{
   if(r==null || r.blockIndex()==null) return new BlockVerifyItemResultDto(null,false,false);
   var block=repo.findById(r.blockIndex());
   boolean matched=block.map(b->Objects.equals(b.recordKey,r.recordKey())&&Objects.equals(b.version,r.version())&&
      Objects.equals(b.recordHash,r.recordHash())).orElse(false);
   return new BlockVerifyItemResultDto(r.blockIndex(),matched,block.map(this::integrity).orElse(false));
  }).toList());
 }
}
