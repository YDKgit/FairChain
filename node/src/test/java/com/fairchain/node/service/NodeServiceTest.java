package com.fairchain.node.service;

import com.fairchain.api.util.Sha256RecordHasher;
import com.fairchain.node.entity.Block;
import com.fairchain.node.repository.BlockRepository;
import com.fairchain.nodeapi.dto.BlockBatchVerifyRequestDto;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyItemDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 노드 한 대의 동작 — 실제 DB(H2)에 기록하고 검증한다 */
@DataJpaTest
class NodeServiceTest {

    private static final String ZERO = "0".repeat(64);
    private static final String RECORD = "b".repeat(64);
    private static final String OTHER = "c".repeat(64);

    @Autowired BlockRepository blocks;

    private NodeService service;

    @BeforeEach
    void setUp() {
        service = new NodeService(blocks);
    }

    private static String key(int n) {
        return String.format("%064x", n);
    }

    private BlockResultDto submit(int ticket, long version, String recordHash) {
        return service.submit(new BlockRecordDto(key(ticket), version, recordHash));
    }

    @Test
    void firstRecordBecomesBlockOneLinkedToGenesis() {
        BlockResultDto result = submit(1, 1, RECORD);

        assertTrue(result.accepted());
        assertEquals(1L, result.blockIndex());
        Block block = blocks.findById(1L).orElseThrow();
        assertEquals(ZERO, block.prevBlockHash);
        assertEquals(new Sha256RecordHasher().hash("1", ZERO, key(1), "1", RECORD), block.blockHash,
                "블록 해시에는 노드 시각이 들어가지 않는다 — 같은 기록이면 어느 노드에서나 같은 값");
    }

    @Test
    void blocksAreChainedByThePreviousHash() {
        submit(1, 1, RECORD);
        submit(2, 1, RECORD);

        Block first = blocks.findById(1L).orElseThrow();
        Block second = blocks.findById(2L).orElseThrow();
        assertEquals(first.blockHash, second.prevBlockHash);
        assertEquals(2L, service.ledgerStatus().height());
        assertEquals(second.blockHash, service.ledgerStatus().headHash());
        assertTrue(service.ledgerStatus().chainValid());
    }

    @Test
    void sameRecordAgainReturnsTheSameBlockWithoutCreatingANewOne() {
        assertEquals(1L, submit(1, 1, RECORD).blockIndex());
        submit(2, 1, RECORD);

        BlockResultDto again = submit(1, 1, RECORD);

        assertTrue(again.accepted());
        assertEquals(1L, again.blockIndex());
        assertEquals(2, blocks.count());
    }

    @Test
    void versionMustGrowForTheSameTicket() {
        submit(1, 2, RECORD);

        assertFalse(submit(1, 2, OTHER).accepted(), "같은 버전의 다른 지문");
        assertFalse(submit(1, 1, OTHER).accepted(), "더 낮은 버전");
        assertTrue(submit(1, 3, OTHER).accepted());
        assertEquals(2, blocks.count());
    }

    @Test
    void verifyComparesWithTheLatestBlockOfTheTicket() {
        submit(1, 1, RECORD);
        submit(1, 2, OTHER);

        BlockVerifyResultDto latest = service.verify(new BlockVerifyDto(key(1), 2L, OTHER));
        BlockVerifyResultDto stale = service.verify(new BlockVerifyDto(key(1), 1L, RECORD));
        BlockVerifyResultDto forged = service.verify(new BlockVerifyDto(key(1), 2L, RECORD));
        BlockVerifyResultDto unknown = service.verify(new BlockVerifyDto(key(9), 1L, RECORD));

        assertTrue(latest.valid());
        assertFalse(stale.valid());
        assertEquals(2L, stale.latestVersion());
        assertEquals(OTHER, stale.latestRecordHash());
        assertFalse(forged.valid());
        assertFalse(unknown.valid());
        assertEquals(null, unknown.latestVersion());
    }

    @Test
    void batchReportsMatchAndChainStatePerBlock() {
        submit(1, 1, RECORD);
        submit(2, 1, RECORD);

        var results = service.batch(new BlockBatchVerifyRequestDto(List.of(
                new BlockVerifyItemDto(1L, key(1), 1L, RECORD),
                new BlockVerifyItemDto(2L, key(2), 1L, OTHER),
                new BlockVerifyItemDto(9L, key(9), 1L, RECORD)))).results();

        assertTrue(results.get(0).matched());
        assertTrue(results.get(0).confirmed());
        assertFalse(results.get(1).matched());
        assertTrue(results.get(1).confirmed());
        assertFalse(results.get(2).matched());
        assertFalse(results.get(2).confirmed(), "없는 블록");
    }

    @Test
    void proofReturnsTheLatestBlockOfTheTicket() {
        submit(1, 1, RECORD);
        submit(1, 2, OTHER);

        var proof = service.proof(key(1));

        assertTrue(proof.valid());
        assertEquals(2L, proof.blockIndex());
        assertEquals(2L, proof.version());
        assertEquals(OTHER, proof.recordHash());
        assertFalse(service.proof(key(9)).valid());
        assertFalse(service.proof("not-a-key").valid());
    }

    @Test
    void tamperedBlockIsDetectedOnVerifyEvenForOtherTickets() {
        submit(1, 1, RECORD);
        submit(2, 1, RECORD);

        Block tampered = blocks.findById(1L).orElseThrow();
        tampered.recordHash = OTHER;
        blocks.saveAndFlush(tampered);

        assertFalse(service.verify(new BlockVerifyDto(key(2), 1L, RECORD)).valid(), "오래된 블록의 조작도 체인 전체를 무효로 만든다");
        assertFalse(service.ledgerStatus().chainValid());
        assertFalse(service.proof(key(2)).valid());
        assertFalse(service.batch(new BlockBatchVerifyRequestDto(List.of(new BlockVerifyItemDto(2L, key(2), 1L, RECORD))))
                .results().get(0).confirmed());
    }

    @Test
    void nodeWithABrokenChainStopsAcceptingRecords() {
        submit(1, 1, RECORD);
        submit(2, 1, RECORD);

        Block tampered = blocks.findById(1L).orElseThrow();
        tampered.recordHash = OTHER;
        blocks.saveAndFlush(tampered);
        assertFalse(service.auditChain());      // 주기 점검이 조작을 발견

        assertFalse(submit(3, 1, RECORD).accepted());
        assertEquals(2, blocks.count());
    }

    @Test
    void missingBlockBreaksTheChain() {
        submit(1, 1, RECORD);
        submit(2, 1, RECORD);
        submit(3, 1, RECORD);

        blocks.deleteById(2L);
        blocks.flush();

        assertFalse(service.ledgerStatus().chainValid());
        assertFalse(service.verify(new BlockVerifyDto(key(3), 1L, RECORD)).valid());
    }

    @Test
    void emptyLedgerStartsAtGenesis() {
        var status = service.ledgerStatus();

        assertEquals(0L, status.height());
        assertEquals(ZERO, status.headHash());
        assertTrue(status.chainValid());
        assertTrue(status.pendingIndices().isEmpty());
    }

    @Test
    void malformedInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.submit(null));
        assertThrows(IllegalArgumentException.class, () -> service.submit(new BlockRecordDto("not-hex", 1L, RECORD)));
        assertThrows(IllegalArgumentException.class, () -> service.submit(new BlockRecordDto(key(1), 0L, RECORD)));
        assertThrows(IllegalArgumentException.class, () -> service.batch(new BlockBatchVerifyRequestDto(null)));
        assertFalse(service.verify(new BlockVerifyDto(null, null, null)).valid());
        assertFalse(service.batch(new BlockBatchVerifyRequestDto(List.of(new BlockVerifyItemDto(1L, null, null, null))))
                .results().get(0).matched());
        assertEquals(0, blocks.count());
    }
}
