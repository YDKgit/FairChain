package com.fairchain.server.service;

import com.fairchain.api.dto.*;
import com.fairchain.server.entity.AnchorRecord;
import com.fairchain.server.repository.AnchorRecordRepository;
import com.fairchain.nodeapi.dto.*;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TicketLifecycleTest {
 static final String KEY="a".repeat(64), HASH="b".repeat(64);
 AnchorRecordRepository repo=mock(AnchorRecordRepository.class);
 NodeClient node=mock(NodeClient.class);
 AnchorService service=new AnchorService(repo,node,mock(TicketWriteCoordinator.class));
 AnchorRecord anchor(RecordType type,long version, BlockState blockState) {
  var a=new AnchorRecord();a.clientServerId="c";a.recordKey=KEY;
  a.recordHash=HASH;a.eventId=10L;a.type=type;a.version=version;
  a.blockState=blockState;a.blockIndex=1L;return a;
 }
 @Test void purchasedTicketNeedsLedgerApproval() {
  when(repo.findTopByClientServerIdAndRecordKeyOrderByVersionDesc("c",KEY)).thenReturn(Optional.of(anchor(RecordType.PURCHASE,2,BlockState.CONFIRMED)));
  assertEquals("NODE_VERIFICATION_FAILED",service.ticketStatus("c",KEY).reason());
  when(node.verifyQuorum(new BlockVerifyDto(KEY,2L,HASH))).thenReturn(true);
  assertTrue(service.ticketStatus("c",KEY).valid());
 }
 @Test void cancelledAndCheckedInAreNeverValid() {
  for(RecordType type: new RecordType[]{RecordType.CANCEL,RecordType.CHECKIN}) {
   var a=anchor(type,3,BlockState.CONFIRMED);
   when(repo.findTopByClientServerIdAndRecordKeyOrderByVersionDesc("c",KEY)).thenReturn(Optional.of(a));
   when(node.verifyQuorum(new BlockVerifyDto(KEY,3L,HASH))).thenReturn(true);
   assertFalse(service.ticketStatus("c",KEY).valid());
  }
 }
 @Test void pendingFailClosedWithoutCallingNodes() {
  when(repo.findTopByClientServerIdAndRecordKeyOrderByVersionDesc("c",KEY)).thenReturn(Optional.of(anchor(RecordType.PURCHASE,2,BlockState.PENDING)));
  assertEquals("ANCHOR_NOT_CONFIRMED",service.ticketStatus("c",KEY).reason());
  verifyNoInteractions(node);
 }
}
