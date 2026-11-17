package com.fairchain.server.block.requester;

import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;

import java.util.List;

/** 노드 원장 조회 (시연 · 점검 화면용). 노드 비밀값은 서버에만 있으므로 브라우저 대신 FairChain이 묻는다 */
public interface NodeLedgerAccess {

    /**
     * 노드 한 대의 원장 상태.
     *
     * @param number    1부터 시작하는 노드 번호 (설정 순서)
     * @param url       노드 주소
     * @param reachable 응답했으면 true
     * @param status    원장 상태 (응답 없으면 null)
     * @param error     응답 없을 때 사유
     */
    record NodeLedger(int number, String url, boolean reachable, NodeLedgerStatusDto status, String error) {

        public long height() {
            return status == null || status.height() == null ? 0L : status.height();
        }

        public boolean chainValid() {
            return status != null && status.chainValid();
        }
    }

    /** 노드마다 원장 상태 (설정 순서) */
    List<NodeLedger> ledgers();

    /** 노드 한 대의 블록 — from번부터 limit개 (오래된 순). 응답 없으면 빈 목록 */
    List<BlockDto> blocks(int number, long from, int limit);
}
