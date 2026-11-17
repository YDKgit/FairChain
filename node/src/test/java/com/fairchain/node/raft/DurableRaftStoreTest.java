package com.fairchain.node.raft;

import com.fairchain.nodeapi.dto.BlockProposalDto;
import io.microraft.model.RaftModelFactory;
import io.microraft.model.impl.DefaultRaftModelFactory;
import io.microraft.persistence.RestoredRaftState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 재시작한 노드가 임기 · 투표 · 로그를 잊지 않는지 (계획 4.7) */
class DurableRaftStoreTest {

    private final RaftModelFactory f = new DefaultRaftModelFactory();
    private final RaftMessageCodec codec = new RaftMessageCodec(f);
    private final NodeEndpoint a = new NodeEndpoint("A1");
    private final NodeEndpoint b = new NodeEndpoint("B1");
    private final NodeEndpoint c = new NodeEndpoint("C1");

    @TempDir
    Path dir;

    private void persistBasics(DurableRaftStore store, NodeEndpoint votedFor) {
        store.persistAndFlushLocalEndpoint(f.createRaftEndpointPersistentStateBuilder().setLocalEndpoint(a).setVoting(true).build());
        store.persistAndFlushInitialGroupMembers(f.createRaftGroupMembersViewBuilder().setLogIndex(0)
                .setMembers(List.of(a, b, c)).setVotingMembers(List.of(a, b, c)).build());
        store.persistAndFlushTerm(f.createRaftTermPersistentStateBuilder().setTerm(5).setVotedFor(votedFor).build());
        BlockProposalDto block = new BlockProposalDto(1L, "0".repeat(64), "b".repeat(64), 1L, "c".repeat(64), 1000L, "d".repeat(64));
        store.persistLogEntries(List.of(
                f.createLogEntryBuilder().setIndex(1).setTerm(5).setOperation(new LedgerOp.NewTerm()).build(),
                f.createLogEntryBuilder().setIndex(2).setTerm(5).setOperation(new LedgerOp.AppendBlock(block)).build()));
        store.flush();
    }

    /** 리더 메시지로 임기만 올라간 팔로워 — 투표한 상대가 없다. MicroRaft 0.7 SQLite 저장소만 쓰면 이 상태를 잃는다 */
    @Test
    void followerWithoutAVoteKeepsItsTermAndLog() {
        File file = dir.resolve("a1-raft.sqlite").toFile();
        DurableRaftStore store = DurableRaftStore.open(file, codec);
        persistBasics(store, null);
        store.close();

        DurableRaftStore reopened = DurableRaftStore.open(file, codec);
        RestoredRaftState state = reopened.restoredState().orElseThrow();
        assertEquals(a, state.getLocalEndpointPersistentState().getLocalEndpoint());
        assertEquals(5, state.getTermPersistentState().getTerm());
        assertNull(state.getTermPersistentState().getVotedFor());
        assertEquals(2, state.getLogEntries().size());
        assertTrue(state.getLogEntries().get(1).getOperation() instanceof LedgerOp.AppendBlock);
        assertEquals(List.of(a, b, c), List.copyOf(state.getInitialGroupMembers().getMembers()));
        reopened.close();
    }

    /**
     * 감싸는 이유를 남겨 두는 테스트: MicroRaft 0.7의 RaftSqliteStore만 쓰면 위 상태를 "저장된 것 없음"으로 읽는다.
     * MicroRaft를 올렸을 때 이 테스트가 실패하면 라이브러리가 고쳐진 것이니 DurableRaftStore의 우회를 걷어 내도 된다.
     */
    @Test
    void plainSqliteStoreOfMicroRaft07ForgetsAFollowerWithoutAVote() {
        File file = dir.resolve("plain-raft.sqlite").toFile();
        io.microraft.store.sqlite.RaftSqliteStore plain = io.microraft.store.sqlite.RaftSqliteStore.create(file, f,
                new JsonRaftStoreSerializer(codec));
        plain.persistAndFlushLocalEndpoint(f.createRaftEndpointPersistentStateBuilder().setLocalEndpoint(a).setVoting(true).build());
        plain.persistAndFlushInitialGroupMembers(f.createRaftGroupMembersViewBuilder().setLogIndex(0)
                .setMembers(List.of(a, b, c)).setVotingMembers(List.of(a, b, c)).build());
        plain.persistAndFlushTerm(f.createRaftTermPersistentStateBuilder().setTerm(5).setVotedFor(null).build());
        plain.flush();
        assertTrue(plain.getRestoredRaftState(false).isEmpty());
        plain.onRaftNodeTerminate();
    }

    @Test
    void voteIsKept() {
        File file = dir.resolve("a1-raft.sqlite").toFile();
        DurableRaftStore store = DurableRaftStore.open(file, codec);
        persistBasics(store, b);
        store.close();

        DurableRaftStore reopened = DurableRaftStore.open(file, codec);
        assertEquals(b, reopened.restoredState().orElseThrow().getTermPersistentState().getVotedFor());
        reopened.close();
    }

    @Test
    void freshFileHasNoState() {
        DurableRaftStore store = DurableRaftStore.open(dir.resolve("new/x-raft.sqlite").toFile(), codec);
        assertTrue(store.restoredState().isEmpty());
        store.close();
    }
}
