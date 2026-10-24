package com.fairchain.node.service;

import com.fairchain.api.util.Sha256RecordHasher;
import com.fairchain.node.entity.Block;
import com.fairchain.node.repository.BlockRepository;
import com.fairchain.node.repository.PendingBlockRepository;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 3대 중 2대 합의 — 실제 DB(H2)와 가짜 노드 2대로 한 노드의 동작을 확인한다 */
@DataJpaTest
class NodeQuorumTest {

    private static final String ZERO = "0".repeat(64);
    private static final String RECORD = "b".repeat(64);

    @Autowired BlockRepository blocks;
    @Autowired PendingBlockRepository pending;

    private final Sha256RecordHasher hasher = new Sha256RecordHasher();
    private StubPeer first;
    private StubPeer second;
    private NodeService service;

    @BeforeEach
    void setUp() throws Exception {
        first = new StubPeer(true);
        second = new StubPeer(false);
        service = new NodeService(blocks, pending, first.url() + "," + second.url(), "test-secret");
    }

    @AfterEach
    void tearDown() {
        first.close();
        second.close();
    }

    private static String key(int n) {
        return String.format("%064x", n);
    }

    private BlockProposalDto proposal(long index, String prev, String key, long version, long timestamp) {
        String hash = hasher.hash(Long.toString(index), prev, key, Long.toString(version), RECORD);
        return new BlockProposalDto(index, prev, key, version, RECORD, timestamp, hash);
    }

    /** 서로 이어지는 확정 블록 count개 */
    private List<BlockProposalDto> chainOf(int count) {
        List<BlockProposalDto> chain = new ArrayList<>();
        String prev = ZERO;
        for (int i = 1; i <= count; i++) {
            BlockProposalDto block = proposal(i, prev, key(i), 1, 1000L * i);
            chain.add(block);
            prev = block.blockHash();
        }
        return chain;
    }

    @Test
    void quorumIsAMajorityOfTheWholeMemberList() throws Exception {
        // 5대 명단이면 나 + 2표 = 3표가 있어야 확정. 나 + 1표로는 안 된다
        try (StubPeer third = new StubPeer(false); StubPeer fourth = new StubPeer(false)) {
            String four = first.url() + "," + second.url() + "," + third.url() + "," + fourth.url();
            NodeService five = new NodeService(blocks, pending, four, "test-secret");
            assertFalse(five.submit(new BlockRecordDto(key(1), 1L, RECORD)).accepted());
            third.approves = true;
            assertTrue(five.submit(new BlockRecordDto(key(1), 1L, RECORD)).accepted());
        }
    }

    @Test
    void sameRecordProposedAtDifferentTimesIsTheSameBlock() {
        // 노드가 꺼졌다 켜지며 같은 기록을 각자 다른 시각에 제안해도 표가 갈라지지 않아야 한다
        assertTrue(service.propose(proposal(1, ZERO, key(1), 1, 1000)));
        assertTrue(service.propose(proposal(1, ZERO, key(1), 1, 5000)));
        assertFalse(service.propose(proposal(1, ZERO, key(2), 1, 1000)));     // 같은 번호의 다른 기록에는 반대
    }

    @Test
    void oneApprovalOutOfTwoPeersIsEnoughToCommit() {
        BlockResultDto result = service.submit(new BlockRecordDto(key(1), 1L, RECORD));

        assertTrue(result.accepted());
        assertEquals(1L, result.blockIndex());
        assertEquals(1, blocks.count());
        assertEquals(0, pending.count());
        assertEquals(1, first.decisions.size());
        assertEquals(1, first.confirms.size());
        // 찬성하지 않은 노드에는 확정을 보내지 않는다
        assertTrue(second.decisions.isEmpty());
        assertTrue(second.confirms.isEmpty());
    }

    @Test
    void withoutMajorityNothingIsCommittedAndTheRetryCommitsExactlyOnce() {
        first.approves = false;
        BlockRecordDto record = new BlockRecordDto(key(1), 1L, RECORD);

        assertFalse(service.submit(record).accepted());
        assertEquals(0, blocks.count());
        assertEquals(1, pending.count(), "자기 표는 남아 있어야 다음에 같은 블록으로 마무리된다");
        assertTrue(first.confirms.isEmpty());

        first.approves = true;
        BlockResultDto retried = service.submit(record);

        assertTrue(retried.accepted());
        assertEquals(1L, retried.blockIndex());
        assertEquals(1, blocks.count());
        assertEquals(0, pending.count());
    }

    @Test
    void oneUnreachablePeerDoesNotBlockLaterWrites() {
        second.close();     // 노드 1대가 꺼진 상황

        for (int i = 1; i <= 3; i++) {
            BlockResultDto result = service.submit(new BlockRecordDto(key(i), 1L, RECORD));
            assertTrue(result.accepted(), "기록 " + i);
            assertEquals(i, result.blockIndex());
        }
        assertEquals(3, blocks.count());
        assertEquals(0, pending.count());
    }

    @Test
    void sameRecordSubmittedAgainCreatesNoSecondBlock() {
        BlockRecordDto record = new BlockRecordDto(key(1), 1L, RECORD);

        assertEquals(1L, service.submit(record).blockIndex());
        assertEquals(1L, service.submit(record).blockIndex());

        assertEquals(1, blocks.count());
        assertFalse(service.submit(new BlockRecordDto(key(1), 1L, "c".repeat(64))).accepted(), "같은 버전의 다른 지문은 거절");
    }

    @Test
    void nodeVotesForOnlyOneProposalPerBlockNumber() {
        BlockProposalDto a = proposal(1, ZERO, key(1), 1, 1000);
        BlockProposalDto b = proposal(1, ZERO, key(2), 1, 2000);

        assertTrue(service.propose(a));
        assertFalse(service.propose(b));
        assertTrue(service.propose(a), "같은 제안의 재전송은 다시 찬성");
        assertEquals(1, pending.count());
    }

    @Test
    void voteBecomesABlockOnlyAfterTheCommitDecision() {
        BlockProposalDto a = proposal(1, ZERO, key(1), 1, 1000);
        assertTrue(service.propose(a));

        assertFalse(service.confirm(1L, a.blockHash()));
        assertTrue(service.recordCommitDecision(1L, a.blockHash()));
        assertTrue(service.confirm(1L, a.blockHash()));

        assertEquals(1, blocks.count());
        assertEquals(0, pending.count());
        assertTrue(service.confirm(1L, a.blockHash()), "이미 확정된 같은 블록은 다시 true");
        assertFalse(service.confirm(1L, "f".repeat(64)));
    }

    @Test
    void laggingNodeCatchesUpFromAPeerAndDropsItsOutdatedVote() {
        // 꺼져 있는 동안 다른 노드들은 3번 블록까지 확정했고, 이 노드에는 1번에 대한 옛 표가 남아 있다
        assertTrue(service.propose(proposal(1, ZERO, key(9), 1, 500)));
        first.chain = chainOf(3);

        assertEquals(3, service.synchronize());

        assertEquals(3, blocks.count());
        assertEquals(0, pending.count());
        assertEquals(3L, service.ledgerStatus().height());
        assertTrue(service.ledgerStatus().chainValid());
    }

    @Test
    void proposalAheadOfTheLocalChainTriggersCatchUpBeforeVoting() {
        List<BlockProposalDto> chain = chainOf(2);
        first.chain = chain;
        BlockProposalDto next = proposal(3, chain.get(1).blockHash(), key(3), 1, 3000);

        assertTrue(service.propose(next));

        assertEquals(2, blocks.count());
        assertEquals(List.of(3L), service.ledgerStatus().pendingIndices());
    }

    @Test
    void blocksThatDoNotExtendTheLocalChainAreNotAccepted() {
        List<BlockProposalDto> chain = chainOf(3);
        // 2번 블록의 지문이 바뀐 체인 — 해시가 맞지 않으므로 1번까지만 받는다
        BlockProposalDto forged = new BlockProposalDto(2L, chain.get(0).blockHash(), key(2), 1L, "c".repeat(64), 2000L, chain.get(1).blockHash());
        first.chain = List.of(chain.get(0), forged, chain.get(2));

        assertEquals(1, service.synchronize());
        assertEquals(1, blocks.count());
    }

    @Test
    void nodeWithABrokenChainNeitherVotesNorProposesNorServesBlocks() {
        assertTrue(service.submit(new BlockRecordDto(key(1), 1L, RECORD)).accepted());
        assertTrue(service.submit(new BlockRecordDto(key(2), 1L, RECORD)).accepted());

        Block tampered = blocks.findById(1L).orElseThrow();
        tampered.recordHash = "e".repeat(64);
        blocks.saveAndFlush(tampered);
        service.maintain();     // 주기 점검이 조작을 발견

        Block head = blocks.findById(2L).orElseThrow();
        assertFalse(service.propose(proposal(3, head.blockHash, key(3), 1, 3000)));
        assertFalse(service.submit(new BlockRecordDto(key(3), 1L, RECORD)).accepted());
        assertTrue(service.blocks(1L, 10).isEmpty());
        assertFalse(service.ledgerStatus().chainValid());
        assertEquals(0, pending.count());
        assertEquals(2, blocks.count());
    }
}
