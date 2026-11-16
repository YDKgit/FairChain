package com.fairchain.node.raft;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Raft 로그 항목 · 스냅샷 조각에 실리는 우리 데이터. JSON에는 "type" 이름으로 구분해 넣는다
 * (MicroRaft 자체 항목인 멤버 변경은 {@link RaftMessageCodec}이 따로 다룬다).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = LedgerOp.AppendBlock.class, name = "AppendBlock"),
        @JsonSubTypes.Type(value = LedgerOp.NewTerm.class, name = "NewTerm"),
        @JsonSubTypes.Type(value = LedgerOp.RegisterMember.class, name = "RegisterMember"),
        @JsonSubTypes.Type(value = LedgerOp.Exclude.class, name = "Exclude"),
        @JsonSubTypes.Type(value = LedgerOp.Restore.class, name = "Restore"),
        @JsonSubTypes.Type(value = LedgerOp.Verdict.class, name = "Verdict"),
        @JsonSubTypes.Type(value = LedgerOp.RemoveMember.class, name = "RemoveMember"),
        @JsonSubTypes.Type(value = LedgerOp.Halt.class, name = "Halt"),
        @JsonSubTypes.Type(value = LedgerOp.Resume.class, name = "Resume"),
        @JsonSubTypes.Type(value = LedgerSnapshot.class, name = "Snapshot")
})
public sealed interface RaftPayload permits LedgerOp, LedgerSnapshot {
}
