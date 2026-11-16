package com.fairchain.node.raft;

import io.microraft.RaftEndpoint;

import java.util.Objects;

/**
 * Raft 멤버 = 노드 하나 (미정사항 1번). ID만 들고 다니고, 주소 · 팀 · 공개키는 멤버 명단({@link MemberRegistry})에서 찾는다.
 * MicroRaft가 멤버를 Map · Set 키로 쓰므로 equals · hashCode는 ID로만 정한다.
 */
public record NodeEndpoint(String id) implements RaftEndpoint {

    public NodeEndpoint {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Blank node id");
        }
    }

    @Override
    public Object getId() {
        return id;
    }

    @Override
    public String toString() {
        return id;
    }
}
