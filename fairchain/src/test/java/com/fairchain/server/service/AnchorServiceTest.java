package com.fairchain.server.service;

import com.fairchain.server.entity.AnchorRecord;
import com.fairchain.server.repository.AnchorRecordRepository;
import com.fairchain.api.dto.BlockState;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnchorServiceTest {
 private static final String KEY="a".repeat(64), HASH="b".repeat(64);
 private final AnchorRecordRepository repo=mock(AnchorRecordRepository.class);
 private final NodeClient nodes=mock(NodeClient.class);
 private final AnchorService service=new AnchorService(repo,nodes,mock(TicketWriteCoordinator.class));
 private AnchorRecord anchor(BlockState state) {
  AnchorRecord a=new AnchorRecord(); a.clientServerId="client1";a.recordKey=KEY;
  a.recordHash=HASH;a.type=com.fairchain.api.dto.RecordType.PURCHASE;a.version=1L;a.blockIndex=1L;a.blockState=state;return a;
 }
 @Test void acceptedOnlyWhenLocalAnchorAndNodesAgree() {
  when(repo.findTopByClientServerIdAndRecordKeyOrderByVersionDesc("client1",KEY)).thenReturn(Optional.of(anchor(BlockState.CONFIRMED)));
  when(nodes.verifyQuorum(new BlockVerifyDto(KEY,1L,HASH))).thenReturn(true);
  assertEquals("VERIFIED",service.verifyTicket("client1",KEY,1L,HASH).reason());
  assertTrue(service.verifyTicket("client1",KEY,1L,HASH).valid());
  assertEquals("ANCHOR_MISMATCH",service.verifyTicket("client1",KEY,2L,HASH).reason());
  assertEquals("ANCHOR_MISMATCH",service.verifyTicket("client1",KEY,1L,"c".repeat(64)).reason());
 }
 @Test void unconfirmedOrMissingAnchorNeverQueriesNodes() {
  when(repo.findTopByClientServerIdAndRecordKeyOrderByVersionDesc("client1",KEY)).thenReturn(Optional.of(anchor(BlockState.PENDING)));
  assertEquals("ANCHOR_NOT_CONFIRMED",service.verifyTicket("client1",KEY,1L,HASH).reason());
  assertEquals("ANCHOR_NOT_FOUND",service.verifyTicket("another",KEY,1L,HASH).reason());
  verifyNoInteractions(nodes);
 }
 @Test void nodeFailureDeniesTicket() {
  when(repo.findTopByClientServerIdAndRecordKeyOrderByVersionDesc("client1",KEY)).thenReturn(Optional.of(anchor(BlockState.CONFIRMED)));
  assertEquals("NODE_VERIFICATION_FAILED",service.verifyTicket("client1",KEY,1L,HASH).reason());
 }
}
