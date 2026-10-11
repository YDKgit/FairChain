package com.fairchain.server.service;

import com.fairchain.api.dto.*;
import com.fairchain.server.entity.AnchorRecord;
import com.fairchain.server.repository.AnchorRecordRepository;
import com.fairchain.nodeapi.dto.*;
import org.springframework.stereotype.Service;
import java.util.regex.Pattern;

@Service public class AnchorService {
 private static final Pattern HEX=Pattern.compile("[0-9a-f]{64}");
 private final AnchorRecordRepository repository;
 private final NodeClient nodes;
 private boolean hex(String v) {return v!=null && HEX.matcher(v).matches();}
 /** Validated lifecycle rules for the anchor record only. Reservation ownership and gate auth remain in other subsystems. */
 private final TicketWriteCoordinator writer;
 public AnchorService(AnchorRecordRepository repository,NodeClient nodes,TicketWriteCoordinator writer){this.repository=repository;this.nodes=nodes;this.writer=writer;}
 /** A durable PENDING row is committed before any Node request. Fail closed on uncertainty. */
 public AnchorRecord record(RecordRequestDto dto,String clientId){
  if(dto==null || clientId==null || clientId.isBlank() || dto.type()==null || dto.eventId()==null ||
     dto.version()==null || dto.version()<1 || !hex(dto.recordKey()) || !hex(dto.recordHash()))
   throw new IllegalArgumentException("INVALID_RECORD_REQUEST");
  var prepared=writer.prepare(dto,clientId);
  AnchorRecord a=prepared.anchor();
  if(!prepared.newlyCreated())return a;
  // A repeated PENDING request is deliberately not replayed automatically: if the original
  // coordinator is still running a second submission could race. Reconciliation is separate.
  try {
   var result=nodes.write(new BlockRecordDto(dto.recordKey(),dto.version(),dto.recordHash()));
   if(result!=null && result.accepted() && result.blockIndex()!=null)
    return writer.confirm(a.id,result.blockIndex());
  }catch(Exception ignored) { /* PENDING requires reconciliation; no false success */ }
  return a;
 }
 /** No replay/new blockchain write: only acknowledges an existing unanimous proof. */
 public TicketVerificationResult reconcilePending(String clientId,String recordKey) {
  if(clientId==null || clientId.isBlank() || !hex(recordKey))
   return new TicketVerificationResult(false,"INVALID_INPUT",null);
  var found=repository.findTopByClientServerIdAndRecordKeyOrderByVersionDesc(clientId,recordKey);
  if(found.isEmpty())return new TicketVerificationResult(false,"ANCHOR_NOT_FOUND",null);
  var a=found.get();
  if(a.blockState==BlockState.CONFIRMED)
   return new TicketVerificationResult(true,"ALREADY_CONFIRMED",a.blockIndex);
  if(a.blockState!=BlockState.PENDING)return new TicketVerificationResult(false,"NOT_PENDING",null);
  var proof=nodes.unanimousProof(recordKey,a.version,a.recordHash);
  if(proof.isEmpty())return new TicketVerificationResult(false,"PROOF_NOT_UNANIMOUS",null);
  try {
   var confirmed=writer.reconcile(a.id,proof.get().blockIndex());
   return new TicketVerificationResult(true,"RECONCILED",confirmed.blockIndex);
  }catch(RuntimeException ex) {return new TicketVerificationResult(false,"RECONCILIATION_CONFLICT",null);}
 }
 public TicketLifecycleResult ticketStatus(String clientId,String recordKey) {
  if(clientId==null || clientId.isBlank() || !hex(recordKey))
   return new TicketLifecycleResult(false,"INVALID_INPUT",null,null,null);
  var last=repository.findTopByClientServerIdAndRecordKeyOrderByVersionDesc(clientId,recordKey);
  if(last.isEmpty()) return new TicketLifecycleResult(false,"ANCHOR_NOT_FOUND",null,null,null);
  var a=last.get();
  if(a.blockState!=BlockState.CONFIRMED || a.blockIndex==null)
   return new TicketLifecycleResult(false,"ANCHOR_NOT_CONFIRMED",a.type==null?null:a.type.name(),a.version,a.blockIndex);
  // Even CANCEL/CHECKIN must be verified against the ledger before describing their state as confirmed.
  boolean ledger;
  try {ledger=nodes.verifyQuorum(new BlockVerifyDto(recordKey,a.version,a.recordHash));}
  catch(Exception e) {return new TicketLifecycleResult(false,"NODE_UNAVAILABLE",null,a.version,a.blockIndex);}
  if(!ledger) return new TicketLifecycleResult(false,"NODE_VERIFICATION_FAILED",null,a.version,a.blockIndex);
  String state=a.type==null?"UNKNOWN":a.type.name();
  return switch(state) {
   case "PURCHASE" -> new TicketLifecycleResult(true,"VALID",state,a.version,a.blockIndex);
   case "ISSUE" -> new TicketLifecycleResult(false,"NOT_PURCHASED",state,a.version,a.blockIndex);
   case "CANCEL" -> new TicketLifecycleResult(false,"CANCELLED",state,a.version,a.blockIndex);
   case "CHECKIN" -> new TicketLifecycleResult(false,"ALREADY_CHECKED_IN",state,a.version,a.blockIndex);
   default -> new TicketLifecycleResult(false,"UNKNOWN_STATE",state,a.version,a.blockIndex);
  };
 }
 public TicketVerificationResult verifyTicket(String clientId,String recordKey,Long version,String hash) {
  if(!hex(recordKey) || version==null || version<1 || !hex(hash))
   return new TicketVerificationResult(false,"INVALID_INPUT",null);
  var local=repository.findTopByClientServerIdAndRecordKeyOrderByVersionDesc(clientId,recordKey);
  if(local.isEmpty()) return new TicketVerificationResult(false,"ANCHOR_NOT_FOUND",null);
  var a=local.get();
  if(a.blockState!=BlockState.CONFIRMED || a.blockIndex==null)
   return new TicketVerificationResult(false,"ANCHOR_NOT_CONFIRMED",a.blockIndex);
  if(!version.equals(a.version) || !hash.equals(a.recordHash))
   return new TicketVerificationResult(false,"ANCHOR_MISMATCH",a.blockIndex);
  TicketLifecycleResult status=ticketStatus(clientId,recordKey);
  return new TicketVerificationResult(status.valid(),status.reason().equals("VALID")?"VERIFIED":status.reason(),a.blockIndex);
 }
 public boolean verify(String recordKey,Long version,String hash) {
  return nodes.verifyQuorum(new BlockVerifyDto(recordKey,version,hash));
 }
}
