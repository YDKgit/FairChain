package com.fairchain.nodeapi;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 리더 층 노드 ↔ 노드: Raft 메시지 (AppendEntries · Vote · PreVote · InstallSnapshot …) — NodeController가 구현.
 *
 * MicroRaft는 전송 계층을 갖고 있지 않아서(Transport 인터페이스만) HTTP로 옮긴다. 본문은 node 모듈의
 * RaftMessageCodec이 만든 JSON이다. 메시지는 한 방향으로만 가고, 응답 메시지도 같은 경로로 따로 보낸다.
 */
public interface NodeRaftApi {

    String MESSAGES = "/node/raft/messages";

    @PostMapping(path = MESSAGES, consumes = "application/json")
    void receiveRaftMessage(@RequestBody String message);
}
