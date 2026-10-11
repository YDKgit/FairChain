package com.fairchain.server.service;
import com.fairchain.api.dto.BlockState;
import com.fairchain.server.entity.AnchorRecord;
import com.fairchain.server.repository.AnchorRecordRepository;
import com.fairchain.nodeapi.dto.BlockProofDto;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PendingReconciliationTest {
 private static final String KEY="a".repeat(64), HASH="b".repeat(64);
 @Test void refusesMissingConsensusWithoutChangingDatabase() {
  var repo=mock(AnchorRecordRepository.class);var client=mock(NodeClient.class);
  var writer=mock(TicketWriteCoordinator.class);
  var a=new AnchorRecord();a.id=1L;a.clientServerId="c";a.recordKey=KEY;a.version=1L;a.recordHash=HASH;a.blockState=BlockState.PENDING;
  when(repo.findTopByClientServerIdAndRecordKeyOrderByVersionDesc("c",KEY)).thenReturn(Optional.of(a));
  when(client.unanimousProof(KEY,1L,HASH)).thenReturn(Optional.empty());
  var service=new AnchorService(repo,client,writer);
  assertEquals("PROOF_NOT_UNANIMOUS",service.reconcilePending("c",KEY).reason());
  verifyNoInteractions(writer);
 }
 @Test void commitsExactlyMatchedProof() {
  var repo=mock(AnchorRecordRepository.class);var client=mock(NodeClient.class);
  var writer=mock(TicketWriteCoordinator.class);
  var a=new AnchorRecord();a.id=1L;a.clientServerId="c";a.recordKey=KEY;a.version=1L;a.recordHash=HASH;a.blockState=BlockState.PENDING;
  when(repo.findTopByClientServerIdAndRecordKeyOrderByVersionDesc("c",KEY)).thenReturn(Optional.of(a));
  when(client.unanimousProof(KEY,1L,HASH)).thenReturn(Optional.of(new BlockProofDto(true,7L,1L,HASH,"c".repeat(64))));
  var confirmed=new AnchorRecord();confirmed.id=1L;confirmed.blockState=BlockState.CONFIRMED;confirmed.blockIndex=7L;
  when(writer.reconcile(1L,7L)).thenReturn(confirmed);
  var service=new AnchorService(repo,client,writer);
  assertEquals("RECONCILED",service.reconcilePending("c",KEY).reason());
  verify(writer).reconcile(1L,7L);
 }
}
