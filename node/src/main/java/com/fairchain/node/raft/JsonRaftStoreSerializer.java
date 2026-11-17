package com.fairchain.node.raft;

import io.microraft.RaftEndpoint;
import io.microraft.model.log.LogEntry;
import io.microraft.model.log.RaftGroupMembersView;
import io.microraft.model.log.SnapshotChunk;
import io.microraft.model.persistence.RaftEndpointPersistentState;
import io.microraft.model.persistence.RaftTermPersistentState;
import io.microraft.persistence.RaftStoreSerializer;

import java.util.function.Function;

/** Raft 상태 파일(SQLite)에 넣는 형식 — 노드 사이 메시지와 같은 JSON ({@link RaftMessageCodec}) */
public class JsonRaftStoreSerializer implements RaftStoreSerializer {

    private final RaftMessageCodec codec;

    public JsonRaftStoreSerializer(RaftMessageCodec codec) {
        this.codec = codec;
    }

    @Override
    public Serializer<RaftGroupMembersView> raftGroupMembersViewSerializer() {
        return of(codec::encodeMembersView, codec::decodeMembersView);
    }

    @Override
    public Serializer<RaftEndpoint> raftEndpointSerializer() {
        return of(codec::encodeEndpoint, codec::decodeEndpoint);
    }

    @Override
    public Serializer<LogEntry> logEntrySerializer() {
        return of(codec::encodeLogEntry, codec::decodeLogEntry);
    }

    @Override
    public Serializer<SnapshotChunk> snapshotChunkSerializer() {
        return of(codec::encodeSnapshotChunk, codec::decodeSnapshotChunk);
    }

    @Override
    public Serializer<RaftEndpointPersistentState> raftEndpointPersistentStateSerializer() {
        return of(codec::encodeEndpointState, codec::decodeEndpointState);
    }

    @Override
    public Serializer<RaftTermPersistentState> raftTermPersistentState() {
        return of(codec::encodeTermState, codec::decodeTermState);
    }

    private static <T> Serializer<T> of(Function<T, byte[]> encode, Function<byte[], T> decode) {
        return new Serializer<>() {
            @Override
            public byte[] serialize(T element) {
                return encode.apply(element);
            }

            @Override
            public T deserialize(byte[] element) {
                return decode.apply(element);
            }
        };
    }
}
