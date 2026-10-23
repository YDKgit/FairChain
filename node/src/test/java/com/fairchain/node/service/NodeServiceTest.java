package com.fairchain.node.service;

import com.fairchain.api.util.Sha256RecordHasher;
import com.fairchain.node.entity.Block;
import com.fairchain.node.repository.BlockRepository;
import com.fairchain.node.repository.PendingBlockRepository;
import com.fairchain.nodeapi.dto.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeServiceTest {
 private final BlockRepository repo=mock(BlockRepository.class);
 private final PendingBlockRepository pending=mock(PendingBlockRepository.class);
 private final NodeService service=new NodeService(repo,pending,"http://localhost:8082,http://localhost:8083","secret");
 private final Sha256RecordHasher hash=new Sha256RecordHasher();
 private static final String KEY="a".repeat(64), RECORD="b".repeat(64), ZERO="0".repeat(64);
 private Block block(long index,String prev,String key,long version,String digest,long timestamp) {
  String blockHash=hash.hash(Long.toString(index),prev,key,Long.toString(version),digest,Long.toString(timestamp));
  return new Block(index,prev,key,version,digest,LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp),ZoneOffset.UTC),blockHash);
 }
 /** 체인 전체 검사가 읽는 묶음 조회를 준비한다 */
 private void chain(Block... blocks) {
  when(repo.findByBlockIndexGreaterThanEqualOrderByBlockIndexAsc(eq(1L),any())).thenReturn(List.of(blocks));
 }
 @Test void replayOfAlreadyCommittedProposalIsIdempotent() {
  Block committed=block(1,ZERO,KEY,1,RECORD,1000);
  when(repo.findById(1L)).thenReturn(Optional.of(committed));
  var same=new BlockProposalDto(1L,ZERO,KEY,1L,RECORD,1000L,committed.blockHash);
  assertTrue(service.propose(same));
  assertFalse(service.propose(new BlockProposalDto(1L,ZERO,KEY,1L,RECORD,1000L,"f".repeat(64))));
  verify(pending,never()).saveAndFlush(any());
 }
 @Test void proposalIsPersistedForRestartRecovery() {
  Block b=block(1,ZERO,KEY,1,RECORD,1000);
  var proposal=new BlockProposalDto(1L,ZERO,KEY,1L,RECORD,1000L,b.blockHash);
  when(repo.findTopByOrderByBlockIndexDesc()).thenReturn(Optional.empty());
  when(repo.findTopByRecordKeyOrderByBlockIndexDesc(KEY)).thenReturn(Optional.empty());
  assertTrue(service.propose(proposal));
  verify(pending).saveAndFlush(any(com.fairchain.node.entity.PendingBlock.class));
 }
 @Test void conflictingPreparedBlockMustBeRejected() {
  Block b=block(1,ZERO,KEY,1,RECORD,1000);
  var proposal=new BlockProposalDto(1L,ZERO,KEY,1L,RECORD,1000L,b.blockHash);
  var previous=new com.fairchain.node.entity.PendingBlock(1L,ZERO,KEY,1L,RECORD,1000L,"f".repeat(64));
  when(repo.findTopByOrderByBlockIndexDesc()).thenReturn(Optional.empty());
  when(repo.findTopByRecordKeyOrderByBlockIndexDesc(KEY)).thenReturn(Optional.empty());
  when(pending.findById(1L)).thenReturn(Optional.of(previous));
  assertFalse(service.propose(proposal));
 }
 @Test void confirmationWithoutPreparedProposalFails() {
  assertFalse(service.confirm(1L,"a".repeat(64)));
  assertFalse(service.confirm(1L,"invalid"));
 }
 @Test void successfulRepeatedConfirmationMustMatchCommittedHash() {
  Block b=block(1,ZERO,KEY,1,RECORD,1000);
  when(repo.findById(1L)).thenReturn(Optional.of(b));
  assertTrue(service.confirm(1L,b.blockHash));
  assertFalse(service.confirm(1L,"f".repeat(64)));
 }
 @Test void blocksAreListedInOrderFromTheRequestedNumber() {
  Block first=block(1,ZERO,KEY,1,RECORD,1000);
  Block second=block(2,first.blockHash,"c".repeat(64),1,"d".repeat(64),2000);
  when(repo.findByBlockIndexGreaterThanEqualOrderByBlockIndexAsc(eq(2L),any())).thenReturn(List.of(second));
  var listed=service.blocks(2L,10);
  assertEquals(List.of(2L),listed.stream().map(b->b.blockIndex()).toList());
  assertEquals(first.blockHash,listed.get(0).prevBlockHash());
  assertTrue(service.blocks(null,10).isEmpty());
 }
 @Test void verifiesValidGenesisRecord() {
  Block b=block(1,ZERO,KEY,1,RECORD,1000);
  when(repo.findTopByRecordKeyOrderByBlockIndexDesc(KEY)).thenReturn(Optional.of(b));
  when(repo.findTopByOrderByBlockIndexDesc()).thenReturn(Optional.of(b));
  when(repo.findById(1L)).thenReturn(Optional.of(b));
  chain(b);
  assertTrue(service.verify(new BlockVerifyDto(KEY,1L,RECORD)).valid());
  assertFalse(service.verify(new BlockVerifyDto(KEY,2L,RECORD)).valid());
  assertFalse(service.verify(new BlockVerifyDto(KEY,1L,"c".repeat(64))).valid());
 }
 @Test void detectsTamperingInAnOlderBlock() {
  Block first=block(1,ZERO,KEY,1,RECORD,1000);
  Block second=block(2,first.blockHash,"c".repeat(64),1,"d".repeat(64),2000);
  first.recordHash="e".repeat(64);
  when(repo.findTopByRecordKeyOrderByBlockIndexDesc(second.recordKey)).thenReturn(Optional.of(second));
  when(repo.findTopByOrderByBlockIndexDesc()).thenReturn(Optional.of(second));
  when(repo.findById(1L)).thenReturn(Optional.of(first));
  when(repo.findById(2L)).thenReturn(Optional.of(second));
  chain(first,second);
  assertFalse(service.verify(new BlockVerifyDto(second.recordKey,1L,second.recordHash)).valid());
 }
 @Test void detectsMissingPredecessorAndBatchMismatch() {
  Block b=block(2,"f".repeat(64),KEY,1,RECORD,2000);
  when(repo.findTopByOrderByBlockIndexDesc()).thenReturn(Optional.of(b));
  when(repo.findById(1L)).thenReturn(Optional.empty());
  when(repo.findById(2L)).thenReturn(Optional.of(b));
  chain(b);
  var result=service.batch(new BlockBatchVerifyRequestDto(List.of(new BlockVerifyItemDto(2L,KEY,1L,RECORD))));
  assertTrue(result.results().get(0).matched());
  assertFalse(result.results().get(0).confirmed());
 }
 @Test void detectsTamperingAfterVerifiedTicketBlock() {
  Block first=block(1,ZERO,KEY,1,RECORD,1000);
  Block second=block(2,first.blockHash,"c".repeat(64),1,"d".repeat(64),2000);
  second.recordHash="e".repeat(64);
  when(repo.findTopByRecordKeyOrderByBlockIndexDesc(KEY)).thenReturn(Optional.of(first));
  when(repo.findTopByOrderByBlockIndexDesc()).thenReturn(Optional.of(second));
  when(repo.findById(1L)).thenReturn(Optional.of(first));
  when(repo.findById(2L)).thenReturn(Optional.of(second));
  chain(first,second);
  assertFalse(service.verify(new BlockVerifyDto(KEY,1L,RECORD)).valid());
 }
 @Test void malformedBatchRecordDoesNotThrow() {
  Block first=block(1,ZERO,KEY,1,RECORD,1000);
  when(repo.findById(1L)).thenReturn(Optional.of(first));
  when(repo.findTopByOrderByBlockIndexDesc()).thenReturn(Optional.of(first));
  var response=service.batch(new BlockBatchVerifyRequestDto(List.of(new BlockVerifyItemDto(1L,null,null,null))));
  assertFalse(response.results().get(0).matched());
 }
 @Test void invalidVerificationInputFailsClosed() {
  assertFalse(service.verify(new BlockVerifyDto(null,null,null)).valid());
  assertThrows(IllegalArgumentException.class,()->service.batch(new BlockBatchVerifyRequestDto(null)));
 }
 @Test void emptyLedgerHasGenesisHead() {
  when(repo.findTopByOrderByBlockIndexDesc()).thenReturn(Optional.empty());
  when(pending.findAll()).thenReturn(List.of());
  var snapshot=service.ledgerStatus();
  assertEquals(0L,snapshot.height());
  assertEquals(ZERO,snapshot.headHash());
  assertTrue(snapshot.chainValid());
  assertTrue(snapshot.pendingIndices().isEmpty());
 }
 @Test void pendingProposalsAreVisibleToOperator() {
  when(repo.findTopByOrderByBlockIndexDesc()).thenReturn(Optional.empty());
  when(pending.findAll()).thenReturn(List.of(new com.fairchain.node.entity.PendingBlock(1L,ZERO,KEY,1L,RECORD,1000L,"c".repeat(64))));
  assertEquals(List.of(1L),service.ledgerStatus().pendingIndices());
 }

 @Test void recoveryRejectsUnknownOrMismatchingProposal() {
  assertFalse(service.retryPrepared(1L,"a".repeat(64)).accepted());
  var saved=new com.fairchain.node.entity.PendingBlock(1L,ZERO,KEY,1L,RECORD,1000L,"b".repeat(64));
  when(pending.findById(1L)).thenReturn(Optional.of(saved));
  assertFalse(service.retryPrepared(1L,"c".repeat(64)).accepted());
  verify(repo,never()).saveAndFlush(any());
 }
 @Test void recoveryRejectsInvalidChainHeadBeforeContactingPeers() {
  var saved=new com.fairchain.node.entity.PendingBlock(2L,ZERO,KEY,1L,RECORD,1000L,"b".repeat(64));
  when(pending.findById(2L)).thenReturn(Optional.of(saved));
  when(repo.findTopByOrderByBlockIndexDesc()).thenReturn(Optional.empty());
  assertFalse(service.retryPrepared(2L,"b".repeat(64)).accepted());
 }

}
