package com.fairchain.node.raft;

/** 로그에 남기지 않는 읽기 (MicroRaft {@code query}) */
public sealed interface LedgerQuery {

    /** Raft 로그로 정해진 마지막 블록 번호 · 해시 */
    record Head() implements LedgerQuery {}
}
