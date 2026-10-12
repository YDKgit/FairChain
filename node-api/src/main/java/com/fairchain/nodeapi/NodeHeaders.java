package com.fairchain.nodeapi;

/** 노드 요청 공용 헤더 이름 — FairChain → 노드, 노드 ↔ 노드 모두 같은 공유 비밀값을 보낸다 */
public final class NodeHeaders {

    public static final String NODE_SECRET = "X-Node-Secret";

    private NodeHeaders() {}
}
