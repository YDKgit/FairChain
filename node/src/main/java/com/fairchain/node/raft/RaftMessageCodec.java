package com.fairchain.node.raft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.microraft.MembershipChangeMode;
import io.microraft.RaftEndpoint;
import io.microraft.model.RaftModelFactory;
import io.microraft.model.groupop.UpdateRaftGroupMembersOp;
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
import io.microraft.model.persistence.RaftEndpointPersistentState;
import io.microraft.model.persistence.RaftTermPersistentState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * MicroRaft 모델 ↔ JSON.
 *
 * MicroRaft에는 네트워크 전송도 직렬화도 없어서(Transport · RaftStoreSerializer 인터페이스만 있음) 우리가 만든다.
 * 노드 사이 HTTP 메시지({@link HttpRaftTransport})와 Raft 상태 파일({@link JsonRaftStoreSerializer})이 같은 형식을 쓴다.
 * Java 직렬화는 쓰지 않는다 — 네트워크로 받은 바이트를 Java 객체로 풀면 역직렬화 공격이 가능하기 때문이다.
 *
 * 각 메시지 형식의 필드는 MicroRaft 0.7의 인터페이스(io.microraft.model.message.*)와 하나씩 대응하고,
 * 받는 쪽은 {@link RaftModelFactory}의 빌더로 다시 만든다.
 */
public class RaftMessageCodec {

    private static final String UPDATE_MEMBERS = "UpdateMembers";

    private final ObjectMapper json = JsonMapper.builder().build();
    private final RaftModelFactory factory;

    public RaftMessageCodec(RaftModelFactory factory) {
        this.factory = factory;
    }

    public RaftModelFactory factory() {
        return factory;
    }

    // ------------------------------------------------------------------ 메시지

    public byte[] encode(RaftMessage message) {
        return bytes(messageNode(message));
    }

    public RaftMessage decode(byte[] bytes) {
        return message(tree(bytes));
    }

    public RaftMessage decode(String text) {
        try {
            return message(json.readTree(text));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ObjectNode messageNode(RaftMessage m) {
        ObjectNode n = json.createObjectNode();
        n.put("groupId", String.valueOf(m.getGroupId()));
        n.put("sender", id(m.getSender()));
        n.put("term", m.getTerm());
        if (m instanceof AppendEntriesRequest r) {
            n.put("type", "AppendEntriesRequest");
            n.put("previousLogTerm", r.getPreviousLogTerm());
            n.put("previousLogIndex", r.getPreviousLogIndex());
            n.put("commitIndex", r.getCommitIndex());
            ArrayNode entries = n.putArray("logEntries");
            for (LogEntry e : r.getLogEntries()) {
                entries.add(logEntryNode(e));
            }
            n.put("querySequenceNumber", r.getQuerySequenceNumber());
            n.put("flowControlSequenceNumber", r.getFlowControlSequenceNumber());
        } else if (m instanceof AppendEntriesSuccessResponse r) {
            n.put("type", "AppendEntriesSuccessResponse");
            n.put("lastLogIndex", r.getLastLogIndex());
            n.put("querySequenceNumber", r.getQuerySequenceNumber());
            n.put("flowControlSequenceNumber", r.getFlowControlSequenceNumber());
        } else if (m instanceof AppendEntriesFailureResponse r) {
            n.put("type", "AppendEntriesFailureResponse");
            n.put("expectedNextIndex", r.getExpectedNextIndex());
            n.put("querySequenceNumber", r.getQuerySequenceNumber());
            n.put("flowControlSequenceNumber", r.getFlowControlSequenceNumber());
        } else if (m instanceof InstallSnapshotRequest r) {
            n.put("type", "InstallSnapshotRequest");
            n.put("senderLeader", r.isSenderLeader());
            n.put("snapshotTerm", r.getSnapshotTerm());
            n.put("snapshotIndex", r.getSnapshotIndex());
            n.put("totalSnapshotChunkCount", r.getTotalSnapshotChunkCount());
            if (r.getSnapshotChunk() != null) {
                n.set("snapshotChunk", snapshotChunkNode(r.getSnapshotChunk()));
            }
            n.set("snapshottedMembers", endpointsNode(r.getSnapshottedMembers()));
            n.set("groupMembersView", membersViewNode(r.getGroupMembersView()));
            n.put("querySequenceNumber", r.getQuerySequenceNumber());
            n.put("flowControlSequenceNumber", r.getFlowControlSequenceNumber());
        } else if (m instanceof InstallSnapshotResponse r) {
            n.put("type", "InstallSnapshotResponse");
            n.put("snapshotIndex", r.getSnapshotIndex());
            n.put("requestedSnapshotChunkIndex", r.getRequestedSnapshotChunkIndex());
            n.put("querySequenceNumber", r.getQuerySequenceNumber());
            n.put("flowControlSequenceNumber", r.getFlowControlSequenceNumber());
        } else if (m instanceof PreVoteRequest r) {
            n.put("type", "PreVoteRequest");
            n.put("lastLogTerm", r.getLastLogTerm());
            n.put("lastLogIndex", r.getLastLogIndex());
        } else if (m instanceof PreVoteResponse r) {
            n.put("type", "PreVoteResponse");
            n.put("granted", r.isGranted());
        } else if (m instanceof VoteRequest r) {
            n.put("type", "VoteRequest");
            n.put("lastLogTerm", r.getLastLogTerm());
            n.put("lastLogIndex", r.getLastLogIndex());
            n.put("sticky", r.isSticky());
        } else if (m instanceof VoteResponse r) {
            n.put("type", "VoteResponse");
            n.put("granted", r.isGranted());
        } else if (m instanceof TriggerLeaderElectionRequest r) {
            n.put("type", "TriggerLeaderElectionRequest");
            n.put("lastLogTerm", r.getLastLogTerm());
            n.put("lastLogIndex", r.getLastLogIndex());
        } else {
            throw new IllegalArgumentException("Unknown Raft message: " + m.getClass().getName());
        }
        return n;
    }

    private RaftMessage message(JsonNode n) {
        String groupId = n.path("groupId").asText();
        RaftEndpoint sender = endpoint(n.get("sender"));
        int term = n.path("term").asInt();
        String type = n.path("type").asText();
        return switch (type) {
            case "AppendEntriesRequest" -> {
                List<LogEntry> entries = new ArrayList<>();
                for (JsonNode e : n.path("logEntries")) {
                    entries.add(logEntry(e));
                }
                yield factory.createAppendEntriesRequestBuilder().setGroupId(groupId).setSender(sender).setTerm(term)
                        .setPreviousLogTerm(n.path("previousLogTerm").asInt())
                        .setPreviousLogIndex(n.path("previousLogIndex").asLong())
                        .setCommitIndex(n.path("commitIndex").asLong())
                        .setLogEntries(entries)
                        .setQuerySequenceNumber(n.path("querySequenceNumber").asLong())
                        .setFlowControlSequenceNumber(n.path("flowControlSequenceNumber").asLong())
                        .build();
            }
            case "AppendEntriesSuccessResponse" -> factory.createAppendEntriesSuccessResponseBuilder()
                    .setGroupId(groupId).setSender(sender).setTerm(term)
                    .setLastLogIndex(n.path("lastLogIndex").asLong())
                    .setQuerySequenceNumber(n.path("querySequenceNumber").asLong())
                    .setFlowControlSequenceNumber(n.path("flowControlSequenceNumber").asLong())
                    .build();
            case "AppendEntriesFailureResponse" -> factory.createAppendEntriesFailureResponseBuilder()
                    .setGroupId(groupId).setSender(sender).setTerm(term)
                    .setExpectedNextIndex(n.path("expectedNextIndex").asLong())
                    .setQuerySequenceNumber(n.path("querySequenceNumber").asLong())
                    .setFlowControlSequenceNumber(n.path("flowControlSequenceNumber").asLong())
                    .build();
            case "InstallSnapshotRequest" -> factory.createInstallSnapshotRequestBuilder()
                    .setGroupId(groupId).setSender(sender).setTerm(term)
                    .setSenderLeader(n.path("senderLeader").asBoolean())
                    .setSnapshotTerm(n.path("snapshotTerm").asInt())
                    .setSnapshotIndex(n.path("snapshotIndex").asLong())
                    .setTotalSnapshotChunkCount(n.path("totalSnapshotChunkCount").asInt())
                    .setSnapshotChunk(n.hasNonNull("snapshotChunk") ? snapshotChunk(n.get("snapshotChunk")) : null)
                    .setSnapshottedMembers(endpoints(n.path("snapshottedMembers")))
                    .setGroupMembersView(membersView(n.get("groupMembersView")))
                    .setQuerySequenceNumber(n.path("querySequenceNumber").asLong())
                    .setFlowControlSequenceNumber(n.path("flowControlSequenceNumber").asLong())
                    .build();
            case "InstallSnapshotResponse" -> factory.createInstallSnapshotResponseBuilder()
                    .setGroupId(groupId).setSender(sender).setTerm(term)
                    .setSnapshotIndex(n.path("snapshotIndex").asLong())
                    .setRequestedSnapshotChunkIndex(n.path("requestedSnapshotChunkIndex").asInt())
                    .setQuerySequenceNumber(n.path("querySequenceNumber").asLong())
                    .setFlowControlSequenceNumber(n.path("flowControlSequenceNumber").asLong())
                    .build();
            case "PreVoteRequest" -> factory.createPreVoteRequestBuilder()
                    .setGroupId(groupId).setSender(sender).setTerm(term)
                    .setLastLogTerm(n.path("lastLogTerm").asInt())
                    .setLastLogIndex(n.path("lastLogIndex").asLong())
                    .build();
            case "PreVoteResponse" -> factory.createPreVoteResponseBuilder()
                    .setGroupId(groupId).setSender(sender).setTerm(term)
                    .setGranted(n.path("granted").asBoolean())
                    .build();
            case "VoteRequest" -> factory.createVoteRequestBuilder()
                    .setGroupId(groupId).setSender(sender).setTerm(term)
                    .setLastLogTerm(n.path("lastLogTerm").asInt())
                    .setLastLogIndex(n.path("lastLogIndex").asLong())
                    .setSticky(n.path("sticky").asBoolean())
                    .build();
            case "VoteResponse" -> factory.createVoteResponseBuilder()
                    .setGroupId(groupId).setSender(sender).setTerm(term)
                    .setGranted(n.path("granted").asBoolean())
                    .build();
            case "TriggerLeaderElectionRequest" -> factory.createTriggerLeaderElectionRequestBuilder()
                    .setGroupId(groupId).setSender(sender).setTerm(term)
                    .setLastLogTerm(n.path("lastLogTerm").asInt())
                    .setLastLogIndex(n.path("lastLogIndex").asLong())
                    .build();
            default -> throw new IllegalArgumentException("Unknown Raft message type: " + type);
        };
    }

    // ------------------------------------------------------------------ 로그 · 스냅샷 · 멤버 (저장 파일에도 쓰임)

    public byte[] encodeLogEntry(LogEntry entry) {
        return bytes(logEntryNode(entry));
    }

    public LogEntry decodeLogEntry(byte[] bytes) {
        return logEntry(tree(bytes));
    }

    public byte[] encodeSnapshotChunk(SnapshotChunk chunk) {
        return bytes(snapshotChunkNode(chunk));
    }

    public SnapshotChunk decodeSnapshotChunk(byte[] bytes) {
        return snapshotChunk(tree(bytes));
    }

    public byte[] encodeMembersView(RaftGroupMembersView view) {
        return bytes(membersViewNode(view));
    }

    public RaftGroupMembersView decodeMembersView(byte[] bytes) {
        return membersView(tree(bytes));
    }

    public byte[] encodeEndpoint(RaftEndpoint endpoint) {
        return bytes(json.getNodeFactory().textNode(id(endpoint)));
    }

    public RaftEndpoint decodeEndpoint(byte[] bytes) {
        return endpoint(tree(bytes));
    }

    public byte[] encodeEndpointState(RaftEndpointPersistentState state) {
        ObjectNode n = json.createObjectNode();
        n.put("localEndpoint", id(state.getLocalEndpoint()));
        n.put("voting", state.isVoting());
        return bytes(n);
    }

    public RaftEndpointPersistentState decodeEndpointState(byte[] bytes) {
        JsonNode n = tree(bytes);
        return factory.createRaftEndpointPersistentStateBuilder()
                .setLocalEndpoint(endpoint(n.get("localEndpoint")))
                .setVoting(n.path("voting").asBoolean())
                .build();
    }

    public byte[] encodeTermState(RaftTermPersistentState state) {
        ObjectNode n = json.createObjectNode();
        n.put("term", state.getTerm());
        if (state.getVotedFor() != null) {
            n.put("votedFor", id(state.getVotedFor()));
        }
        return bytes(n);
    }

    public RaftTermPersistentState decodeTermState(byte[] bytes) {
        JsonNode n = tree(bytes);
        return factory.createRaftTermPersistentStateBuilder()
                .setTerm(n.path("term").asInt())
                .setVotedFor(n.hasNonNull("votedFor") ? endpoint(n.get("votedFor")) : null)
                .build();
    }

    private ObjectNode logEntryNode(LogEntry e) {
        ObjectNode n = json.createObjectNode();
        n.put("index", e.getIndex());
        n.put("term", e.getTerm());
        n.set("operation", operationNode(e.getOperation()));
        return n;
    }

    private LogEntry logEntry(JsonNode n) {
        return factory.createLogEntryBuilder()
                .setIndex(n.path("index").asLong())
                .setTerm(n.path("term").asInt())
                .setOperation(operation(n.get("operation")))
                .build();
    }

    private ObjectNode snapshotChunkNode(SnapshotChunk c) {
        ObjectNode n = json.createObjectNode();
        n.put("index", c.getIndex());
        n.put("term", c.getTerm());
        n.set("operation", operationNode(c.getOperation()));
        n.put("snapshotChunkIndex", c.getSnapshotChunkIndex());
        n.put("snapshotChunkCount", c.getSnapshotChunkCount());
        n.set("groupMembersView", membersViewNode(c.getGroupMembersView()));
        return n;
    }

    private SnapshotChunk snapshotChunk(JsonNode n) {
        return factory.createSnapshotChunkBuilder()
                .setIndex(n.path("index").asLong())
                .setTerm(n.path("term").asInt())
                .setOperation(operation(n.get("operation")))
                .setSnapshotChunkIndex(n.path("snapshotChunkIndex").asInt())
                .setSnapshotChunkCount(n.path("snapshotChunkCount").asInt())
                .setGroupMembersView(membersView(n.get("groupMembersView")))
                .build();
    }

    private ObjectNode membersViewNode(RaftGroupMembersView v) {
        ObjectNode n = json.createObjectNode();
        n.put("logIndex", v.getLogIndex());
        n.set("members", endpointsNode(v.getMembers()));
        n.set("votingMembers", endpointsNode(v.getVotingMembers()));
        return n;
    }

    private RaftGroupMembersView membersView(JsonNode n) {
        return factory.createRaftGroupMembersViewBuilder()
                .setLogIndex(n.path("logIndex").asLong())
                .setMembers(endpoints(n.path("members")))
                .setVotingMembers(endpoints(n.path("votingMembers")))
                .build();
    }

    /** 로그 항목의 내용: 우리 동작(RaftPayload)이거나 MicroRaft의 멤버 변경 */
    private JsonNode operationNode(Object op) {
        if (op instanceof UpdateRaftGroupMembersOp u) {
            ObjectNode n = json.createObjectNode();
            n.put("type", UPDATE_MEMBERS);
            n.set("members", endpointsNode(u.getMembers()));
            n.set("votingMembers", endpointsNode(u.getVotingMembers()));
            n.put("endpoint", id(u.getEndpoint()));
            n.put("mode", u.getMode().name());
            return n;
        }
        if (op instanceof RaftPayload p) {
            try {
                return json.readTree(json.writerFor(RaftPayload.class).writeValueAsBytes(p));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new IllegalArgumentException("Cannot encode Raft operation: " + (op == null ? null : op.getClass().getName()));
    }

    private Object operation(JsonNode n) {
        if (n == null || n.isNull()) {
            throw new IllegalArgumentException("Missing operation");
        }
        if (UPDATE_MEMBERS.equals(n.path("type").asText())) {
            return factory.createUpdateRaftGroupMembersOpBuilder()
                    .setMembers(endpoints(n.path("members")))
                    .setVotingMembers(endpoints(n.path("votingMembers")))
                    .setEndpoint(endpoint(n.get("endpoint")))
                    .setMode(MembershipChangeMode.valueOf(n.path("mode").asText()))
                    .build();
        }
        try {
            return json.treeToValue(n, RaftPayload.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ArrayNode endpointsNode(Collection<RaftEndpoint> endpoints) {
        ArrayNode a = json.createArrayNode();
        for (RaftEndpoint e : endpoints) {
            a.add(id(e));
        }
        return a;
    }

    private List<RaftEndpoint> endpoints(JsonNode a) {
        List<RaftEndpoint> list = new ArrayList<>();
        for (JsonNode n : a) {
            list.add(endpoint(n));
        }
        return list;
    }

    private static String id(RaftEndpoint e) {
        return String.valueOf(e.getId());
    }

    private static RaftEndpoint endpoint(JsonNode n) {
        if (n == null || n.isNull()) {
            throw new IllegalArgumentException("Missing endpoint");
        }
        return new NodeEndpoint(n.asText());
    }

    private byte[] bytes(JsonNode n) {
        try {
            return json.writeValueAsBytes(n);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode tree(byte[] bytes) {
        try {
            return json.readTree(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
