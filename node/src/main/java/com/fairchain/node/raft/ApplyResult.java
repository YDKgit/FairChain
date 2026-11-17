package com.fairchain.node.raft;

import com.fairchain.nodeapi.dto.ReasonCode;

/**
 * 로그 항목을 실행한 결과 — 리더가 FairChain에 돌려줄 답을 만든다.
 *
 * @param blockIndex 확정된 블록 번호 (블록 항목이 아니거나 거부면 null)
 */
public record ApplyResult(ReasonCode reason, Long blockIndex) {

    public static final ApplyResult OK = new ApplyResult(ReasonCode.OK, null);

    public static ApplyResult block(long index) {
        return new ApplyResult(ReasonCode.OK, index);
    }

    public static ApplyResult rejected(ReasonCode reason) {
        return new ApplyResult(reason, null);
    }

    public boolean ok() {
        return reason == ReasonCode.OK;
    }
}
