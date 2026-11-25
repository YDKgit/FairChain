package com.fairchain.node.raft;

import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.ReasonCode;
import com.fairchain.nodeapi.dto.VerdictDto;
import io.microraft.MembershipChangeMode;
import io.microraft.RaftEndpoint;
import io.microraft.model.RaftModelFactory;
import io.microraft.model.impl.DefaultRaftModelFactory;
import io.microraft.model.log.LogEntry;
import io.microraft.model.log.RaftGroupMembersView;
import io.microraft.model.log.SnapshotChunk;
import io.microraft.model.message.AppendEntriesFailureResponse;
import io.microraft.model.message.AppendEntriesRequest;
import io.microraft.model.message.AppendEntriesSuccessResponse;
import io.microraft.model.message.InstallSnapshotRequest;
import io.microraft.model.message.InstallSnapshotResponse;
import io.microraft.model.message.PreVoteRequest;
import io.microraft.model.message.PreVoteResponse;
import io.microraft.model.message.RaftMessage;
import io.microraft.model.message.TriggerLeaderElectionRequest;
import io.microraft.model.message.VoteRequest;
import io.microraft.model.message.VoteResponse;
import io.microraft.model.groupop.UpdateRaftGroupMembersOp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 모든 Raft 메시지 종류가 JSON을 거쳐도 같은 값으로 돌아오는지 */
class RaftMessageCodecTest {

    private final RaftModelFactory f = new DefaultRaftModelFactory();
    private final RaftMessageCodec codec = new RaftMessageCodec(f);
    private final RaftEndpoint a = new NodeEndpoint("A1");
    private final RaftEndpoint b = new NodeEndpoint("B1");
    private final RaftEndpoint c = new NodeEndpoint("C1");
    private static final String H = "a".repeat(64);

    private <T extends RaftMessage> T roundTrip(T message) {
        @SuppressWarnings("unchecked")
        T back = (T) codec.decode(codec.encode(message));
        assertEquals("fairchain", back.getGroupId());
        assertEquals(message.getSender(), back.getSender());
        assertEquals(message.getTerm(), back.getTerm());
        return back;
    }

    private RaftGroupMembersView view() {
        return f.createRaftGroupMembersViewBuilder().setLogIndex(4).setMembers(List.of(a, b, c))
                .setVotingMembers(List.of(a, b)).build();
    }

    @Test
    void appendEntriesRequestWithEveryKindOfOperation() {
        BlockProposalDto block = new BlockProposalDto(7L, H, H, 2L, H, 1_700_000_000_000L, H);
        VerdictDto verdict = new VerdictDto("B1", ReasonCode.DIVERGED, 3L, block, block, List.of("recordHash"),
                "EXCLUDED", "A1", 5L);
        UpdateRaftGroupMembersOp members = f.createUpdateRaftGroupMembersOpBuilder()
                .setMembers(List.of(a, b, c)).setVotingMembers(List.of(a, b)).setEndpoint(c)
                .setMode(MembershipChangeMode.ADD_LEARNER).build();
        List<Object> ops = List.of(new LedgerOp.AppendBlock(block), new LedgerOp.NewTerm(),
                new LedgerOp.RegisterMember("D1", "D", "http://d:9001", "key"), new LedgerOp.Exclude("B1", verdict),
                new LedgerOp.Restore("B1", 9L, H, "A1", 77L), new LedgerOp.Verdict(verdict),
                new LedgerOp.RemoveMember("D1"), new LedgerOp.Halt(verdict), new LedgerOp.Resume("A1", 88L), members);
        List<LogEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            entries.add(f.createLogEntryBuilder().setIndex(10 + i).setTerm(3).setOperation(ops.get(i)).build());
        }
        AppendEntriesRequest sent = f.createAppendEntriesRequestBuilder().setGroupId("fairchain").setSender(a).setTerm(3)
                .setPreviousLogTerm(2).setPreviousLogIndex(9).setCommitIndex(8).setLogEntries(entries)
                .setQuerySequenceNumber(5).setFlowControlSequenceNumber(6).build();

        AppendEntriesRequest got = roundTrip(sent);
        assertEquals(2, got.getPreviousLogTerm());
        assertEquals(9, got.getPreviousLogIndex());
        assertEquals(8, got.getCommitIndex());
        assertEquals(5, got.getQuerySequenceNumber());
        assertEquals(6, got.getFlowControlSequenceNumber());
        assertEquals(ops.size(), got.getLogEntries().size());
        for (int i = 0; i < ops.size() - 1; i++) {
            assertEquals(10 + i, got.getLogEntries().get(i).getIndex());
            assertEquals(3, got.getLogEntries().get(i).getTerm());
            assertEquals(ops.get(i), got.getLogEntries().get(i).getOperation());
        }
        UpdateRaftGroupMembersOp gotMembers = assertInstanceOf(UpdateRaftGroupMembersOp.class,
                got.getLogEntries().get(ops.size() - 1).getOperation());
        assertEquals(List.of(a, b, c), List.copyOf(gotMembers.getMembers()));
        assertEquals(List.of(a, b), List.copyOf(gotMembers.getVotingMembers()));
        assertEquals(c, gotMembers.getEndpoint());
        assertEquals(MembershipChangeMode.ADD_LEARNER, gotMembers.getMode());
    }

    @Test
    void responsesAndElectionMessages() {
        AppendEntriesSuccessResponse ok = roundTrip(f.createAppendEntriesSuccessResponseBuilder().setGroupId("fairchain")
                .setSender(b).setTerm(3).setLastLogIndex(12).setQuerySequenceNumber(1).setFlowControlSequenceNumber(2).build());
        assertEquals(12, ok.getLastLogIndex());
        assertEquals(1, ok.getQuerySequenceNumber());
        assertEquals(2, ok.getFlowControlSequenceNumber());

        AppendEntriesFailureResponse fail = roundTrip(f.createAppendEntriesFailureResponseBuilder().setGroupId("fairchain")
                .setSender(b).setTerm(3).setExpectedNextIndex(4).setQuerySequenceNumber(1).setFlowControlSequenceNumber(2).build());
        assertEquals(4, fail.getExpectedNextIndex());

        PreVoteRequest preVote = roundTrip(f.createPreVoteRequestBuilder().setGroupId("fairchain").setSender(c).setTerm(4)
                .setLastLogTerm(3).setLastLogIndex(12).build());
        assertEquals(3, preVote.getLastLogTerm());
        assertEquals(12, preVote.getLastLogIndex());
        assertTrue(roundTrip(f.createPreVoteResponseBuilder().setGroupId("fairchain").setSender(a).setTerm(4)
                .setGranted(true).build()).isGranted());

        VoteRequest vote = roundTrip(f.createVoteRequestBuilder().setGroupId("fairchain").setSender(c).setTerm(4)
                .setLastLogTerm(3).setLastLogIndex(12).setSticky(true).build());
        assertTrue(vote.isSticky());
        assertEquals(12, vote.getLastLogIndex());
        VoteResponse voteResponse = roundTrip(f.createVoteResponseBuilder().setGroupId("fairchain").setSender(a).setTerm(4)
                .setGranted(false).build());
        assertEquals(false, voteResponse.isGranted());

        TriggerLeaderElectionRequest trigger = roundTrip(f.createTriggerLeaderElectionRequestBuilder().setGroupId("fairchain")
                .setSender(a).setTerm(4).setLastLogTerm(3).setLastLogIndex(12).build());
        assertEquals(12, trigger.getLastLogIndex());
    }

    @Test
    void installSnapshotCarriesTheRegistry() {
        LedgerSnapshot state = new LedgerSnapshot(40, H, List.of(new MemberRecord("A1", "A", "http://a:9001", "key")),
                Map.of("B1", 30L), List.of(), Map.of("C1", 12L), true, List.of("D1"));
        SnapshotChunk chunk = f.createSnapshotChunkBuilder().setIndex(100).setTerm(3).setOperation(state)
                .setSnapshotChunkIndex(0).setSnapshotChunkCount(1).setGroupMembersView(view()).build();
        InstallSnapshotRequest got = roundTrip(f.createInstallSnapshotRequestBuilder().setGroupId("fairchain").setSender(a)
                .setTerm(3).setSenderLeader(true).setSnapshotTerm(3).setSnapshotIndex(100).setTotalSnapshotChunkCount(1)
                .setSnapshotChunk(chunk).setSnapshottedMembers(List.of(a, b)).setGroupMembersView(view())
                .setQuerySequenceNumber(7).setFlowControlSequenceNumber(8).build());
        assertTrue(got.isSenderLeader());
        assertEquals(100, got.getSnapshotIndex());
        assertEquals(state, got.getSnapshotChunk().getOperation());
        assertEquals(1, got.getSnapshotChunk().getSnapshotChunkCount());
        assertEquals(List.of(a, b, c), List.copyOf(got.getGroupMembersView().getMembers()));
        assertEquals(List.of(a, b), List.copyOf(got.getGroupMembersView().getVotingMembers()));
        assertEquals(4, got.getGroupMembersView().getLogIndex());

        InstallSnapshotRequest empty = roundTrip(f.createInstallSnapshotRequestBuilder().setGroupId("fairchain").setSender(a)
                .setTerm(3).setSenderLeader(false).setSnapshotTerm(3).setSnapshotIndex(100).setTotalSnapshotChunkCount(1)
                .setSnapshotChunk(null).setSnapshottedMembers(List.of()).setGroupMembersView(view()).build());
        assertNull(empty.getSnapshotChunk());

        InstallSnapshotResponse response = roundTrip(f.createInstallSnapshotResponseBuilder().setGroupId("fairchain")
                .setSender(b).setTerm(3).setSnapshotIndex(100).setRequestedSnapshotChunkIndex(0).build());
        assertEquals(100, response.getSnapshotIndex());
    }
}
