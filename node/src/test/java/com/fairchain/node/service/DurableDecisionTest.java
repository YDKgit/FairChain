package com.fairchain.node.service;

import com.fairchain.node.entity.PendingBlock;
import com.fairchain.node.repository.BlockRepository;
import com.fairchain.node.repository.PendingBlockRepository;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DurableDecisionTest {
 @Test void unknownDecisionMustFailClosed() {
  var blocks=mock(BlockRepository.class);
  var pending=mock(PendingBlockRepository.class);
  when(pending.findById(1L)).thenReturn(Optional.empty());
  var service=new NodeService(blocks,pending,"http://localhost:1,http://localhost:2","test");
  assertFalse(service.recordCommitDecision(1L,"a".repeat(64)));
  verify(pending,never()).saveAndFlush(any());
 }
 @Test void undecidedPreparedBlockCannotBeConfirmed() {
  var blocks=mock(BlockRepository.class);
  var pending=mock(PendingBlockRepository.class);
  var item=new PendingBlock(1L,"0".repeat(64),"a".repeat(64),1L,"b".repeat(64),123L,"c".repeat(64));
  when(pending.findById(1L)).thenReturn(Optional.of(item));
  var service=new NodeService(blocks,pending,"http://localhost:1,http://localhost:2","test");
  assertFalse(service.confirm(1L,item.blockHash));
  verify(blocks,never()).saveAndFlush(any());
 }
}
