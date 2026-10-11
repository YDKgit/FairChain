package com.fairchain.server.service;
import com.fairchain.api.dto.*;
import com.fairchain.server.entity.*;
import com.fairchain.server.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
/** Persists PENDING before remote HTTP. All transitions for a ticket serialize via the ticket_heads row. */
@Service public class TicketWriteCoordinator {
 private final TicketHeadRepository heads;
 private final AnchorRecordRepository anchors;
 public TicketWriteCoordinator(TicketHeadRepository heads,AnchorRecordRepository anchors){this.heads=heads;this.anchors=anchors;}
 public record PreparedAnchor(AnchorRecord anchor, boolean newlyCreated) {}
 @Transactional public PreparedAnchor prepare(RecordRequestDto dto,String clientId) {
  // Competing first ISSUE attempts cannot both insert due to the unique constraint.
  var head=heads.lockTicket(clientId,dto.recordKey());
  if(head.isEmpty()) {heads.saveAndFlush(new TicketHead(clientId,dto.recordKey()));}
  heads.lockTicket(clientId,dto.recordKey()).orElseThrow();
  var existing=anchors.findTopByClientServerIdAndRecordKeyOrderByVersionDesc(clientId,dto.recordKey());
  if(existing.isPresent()) {
   var last=existing.get();
   if(dto.version().equals(last.version) && dto.recordHash().equals(last.recordHash) && dto.type()==last.type && dto.eventId().equals(last.eventId))return new PreparedAnchor(last,false);
   if(last.blockState!=BlockState.CONFIRMED)throw new IllegalStateException("PREVIOUS_RECORD_PENDING");
   if(dto.version()!=last.version+1)throw new IllegalArgumentException("VERSION_MUST_INCREMENT_BY_ONE");
   if(!dto.eventId().equals(last.eventId))throw new IllegalArgumentException("EVENT_MISMATCH");
   if(!allowed(last.type,dto.type()))throw new IllegalArgumentException("ILLEGAL_TICKET_TRANSITION");
  }else if(dto.version()!=1 || dto.type()!=RecordType.ISSUE)throw new IllegalArgumentException("FIRST_EVENT_MUST_BE_ISSUE_VERSION_1");
  var a=new AnchorRecord();a.clientServerId=clientId;a.eventId=dto.eventId();
  a.userKey=dto.userId()==null?null:String.valueOf(dto.userId());a.recordKey=dto.recordKey();
  a.version=dto.version();a.recordHash=dto.recordHash();a.type=dto.type();
  a.blockState=BlockState.PENDING;a.createdAt=LocalDateTime.now();
  return new PreparedAnchor(anchors.saveAndFlush(a),true);
 }
 private boolean allowed(RecordType prev,RecordType next) {return switch(prev) {
  case ISSUE -> next==RecordType.PURCHASE;
  case PURCHASE -> next==RecordType.CANCEL || next==RecordType.CHECKIN;
  case CANCEL -> next==RecordType.PURCHASE;
  case CHECKIN -> false;
 };}
 /** Under ticket row lock, revalidate that the pending record is still the latest. */
 @Transactional public AnchorRecord reconcile(Long id,Long blockIndex) {
  AnchorRecord requested=anchors.findById(id).orElseThrow();
  heads.lockTicket(requested.clientServerId,requested.recordKey).orElseThrow();
  AnchorRecord current=anchors.findById(id).orElseThrow();
  var latest=anchors.findTopByClientServerIdAndRecordKeyOrderByVersionDesc(current.clientServerId,current.recordKey).orElseThrow();
  if(!id.equals(latest.id))throw new IllegalStateException("NOT_LATEST_ANCHOR");
  if(current.blockState==BlockState.CONFIRMED) {
   if(!blockIndex.equals(current.blockIndex))throw new IllegalStateException("BLOCK_INDEX_CONFLICT");
   return current;
  }
  if(current.blockState!=BlockState.PENDING)throw new IllegalStateException("NOT_PENDING");
  current.blockIndex=blockIndex;current.blockState=BlockState.CONFIRMED;
  return anchors.saveAndFlush(current);
 }
 @Transactional public AnchorRecord confirm(Long id,Long blockIndex){
  var a=anchors.findById(id).orElseThrow();
  if(a.blockState==BlockState.CONFIRMED){
   if(!blockIndex.equals(a.blockIndex))throw new IllegalStateException("BLOCK_INDEX_CONFLICT");
   return a;
  }
  if(a.blockState!=BlockState.PENDING)throw new IllegalStateException("INVALID_ANCHOR_STATE");
  a.blockIndex=blockIndex;a.blockState=BlockState.CONFIRMED;
  return anchors.saveAndFlush(a);
 }
}
