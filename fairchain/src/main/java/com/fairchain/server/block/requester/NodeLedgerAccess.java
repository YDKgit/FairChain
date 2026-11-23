package com.fairchain.server.block.requester;

import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;

import java.util.List;

/** 노드 원장 조회 (시연 · 점검 화면용). 노드 비밀값은 서버에만 있으므로 브라우저 대신 FairChain이 묻는다 */
public interface NodeLedgerAccess {

    /**
     * 노드 한 대의 원장 상태.
     *
     * @param id        노드 ID (예: "A1"). 한 번도 응답을 못 받아 모르면 null
     * @param number    1부터 시작하는 노드 번호 (지금 노드 목록의 순서 — 노드가 추가 · 해지되면 바뀔 수 있다)
     * @param url       노드 주소 (노드를 가리킬 때는 번호가 아니라 이 값을 쓴다)
     * @param reachable 응답했으면 true
     * @param status    원장 상태 (응답 없으면 null)
     * @param error     응답 없을 때 사유
     * @param raft      리더 층 Raft 상태 — 역할 · 임기 · 빠진 이유 · 판정 기록 · 팀원 (예전 노드라 없거나 응답 없으면 null)
     */
    record NodeLedger(int number, String id, String url, boolean reachable, NodeLedgerStatusDto status, String error,
                      NodeStatusDto raft) {

        public long height() {
            return status == null || status.height() == null ? 0L : status.height();
        }

        public boolean chainValid() {
            return status != null && status.chainValid();
        }
    }

    /** 노드마다 원장 상태 — 지금의 노드 목록(리더 층 투표 멤버) 순서 */
    List<NodeLedger> ledgers();

    /** 주소가 url인 노드의 블록 — from번부터 limit개 (오래된 순). 응답 없거나 목록에 없는 노드면 빈 목록 */
    List<BlockDto> blocks(String url, long from, int limit);
}
