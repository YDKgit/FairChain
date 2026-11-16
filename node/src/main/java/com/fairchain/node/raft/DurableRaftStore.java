package com.fairchain.node.raft;

import io.microraft.lifecycle.RaftNodeLifecycleAware;
import io.microraft.model.log.LogEntry;
import io.microraft.model.log.RaftGroupMembersView;
import io.microraft.model.log.SnapshotChunk;
import io.microraft.model.persistence.RaftEndpointPersistentState;
import io.microraft.model.persistence.RaftTermPersistentState;
import io.microraft.persistence.RaftStore;
import io.microraft.persistence.RestoredRaftState;
import io.microraft.store.sqlite.RaftSqliteStore;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * Raft 상태(임기 · 투표 · 로그 · 스냅샷)를 디스크에 남기는 저장소 (계획 4.7). 이게 없으면 재시작한 노드가 자기 임기와 투표를
 * 잊어 한 임기에 리더가 둘 생길 수 있다.
 *
 * MicroRaft의 {@link RaftSqliteStore}를 그대로 쓰되 한 가지만 감싼다: 0.7의 {@code getRestoredRaftState}는 투표한 상대
 * (votedFor)가 비어 있으면 저장된 상태가 없다고 보고 빈 노드로 시작한다. 그런데 리더의 메시지로 임기만 올라간 팔로워는
 * votedFor가 비어 있는 것이 정상이다. 그래서 "투표 안 함"을 표시용 ID로 바꿔 저장하고, 읽을 때 다시 비운다.
 * MicroRaft 코드는 고치지 않는다 (RaftStore 인터페이스 구현만).
 */
public final class DurableRaftStore implements RaftStore, RaftNodeLifecycleAware {

    /** "투표 안 함" 표시. 노드 ID 규칙(영문 · 숫자 · '-' · '_', 첫 글자는 영문 · 숫자)에 맞지 않아 실제 노드와 겹치지 않는다 */
    static final NodeEndpoint NO_VOTE = new NodeEndpoint("~none");

    private final RaftSqliteStore delegate;
    private final RaftMessageCodec codec;

    private DurableRaftStore(RaftSqliteStore delegate, RaftMessageCodec codec) {
        this.delegate = delegate;
        this.codec = codec;
    }

    public static DurableRaftStore open(File file, RaftMessageCodec codec) {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create directory " + parent);
        }
        return new DurableRaftStore(RaftSqliteStore.create(file, codec.factory(), new JsonRaftStoreSerializer(codec)), codec);
    }

    /** 저장된 Raft 상태. 처음 켜는 노드면 empty */
    public Optional<RestoredRaftState> restoredState() {
        return delegate.getRestoredRaftState(true).map(s -> {
            RaftTermPersistentState term = s.getTermPersistentState();
            if (!NO_VOTE.equals(term.getVotedFor())) {
                return s;
            }
            RaftTermPersistentState cleared = codec.factory().createRaftTermPersistentStateBuilder()
                    .setTerm(term.getTerm()).setVotedFor(null).build();
            return new RestoredRaftState(s.getLocalEndpointPersistentState(), s.getInitialGroupMembers(), cleared,
                    s.getSnapshotEntry(), s.getLogEntries());
        });
    }

    @Override
    public void persistAndFlushTerm(RaftTermPersistentState state) {
        RaftTermPersistentState stored = state.getVotedFor() != null ? state
                : codec.factory().createRaftTermPersistentStateBuilder().setTerm(state.getTerm()).setVotedFor(NO_VOTE).build();
        delegate.persistAndFlushTerm(stored);
    }

    @Override
    public void persistAndFlushLocalEndpoint(RaftEndpointPersistentState state) {
        delegate.persistAndFlushLocalEndpoint(state);
    }

    @Override
    public void persistAndFlushInitialGroupMembers(RaftGroupMembersView initialGroupMembers) {
        delegate.persistAndFlushInitialGroupMembers(initialGroupMembers);
    }

    @Override
    public void persistLogEntries(List<LogEntry> logEntries) {
        delegate.persistLogEntries(logEntries);
    }

    @Override
    public void persistSnapshotChunk(SnapshotChunk snapshotChunk) {
        delegate.persistSnapshotChunk(snapshotChunk);
    }

    @Override
    public void truncateLogEntriesFrom(long logIndexInclusive) {
        delegate.truncateLogEntriesFrom(logIndexInclusive);
    }

    @Override
    public void truncateLogEntriesUntil(long logIndexInclusive) {
        delegate.truncateLogEntriesUntil(logIndexInclusive);
    }

    @Override
    public void deleteSnapshotChunks(long logIndex, int snapshotChunkCount) {
        delegate.deleteSnapshotChunks(logIndex, snapshotChunkCount);
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void onRaftNodeStart() {
        delegate.onRaftNodeStart();
    }

    /** 파일을 닫는다 (SQLite 배타 잠금 해제) — 같은 프로세스에서 Raft 노드를 다시 만들 수 있게 */
    @Override
    public void onRaftNodeTerminate() {
        delegate.onRaftNodeTerminate();
    }

    /** Raft 노드를 시작하지 않고 닫을 때 */
    public void close() {
        delegate.onRaftNodeTerminate();
    }
}
