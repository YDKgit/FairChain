package com.fairchain.nodeapi;

/** 노드 요청 공용 헤더 이름 — FairChain → 노드, 노드 ↔ 노드 모두 같은 공유 비밀값을 보낸다 */
public final class NodeHeaders {

    public static final String NODE_SECRET = "X-Node-Secret";

    /** 팔로워가 리더에게 넘긴 요청 표시. 넘겨받은 쪽은 다시 넘기지 않는다 (전달은 한 번만) */
    public static final String FORWARDED = "X-Node-Forwarded";

    private NodeHeaders() {}
}
