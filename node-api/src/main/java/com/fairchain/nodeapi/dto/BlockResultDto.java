package com.fairchain.nodeapi.dto;

/**
 * @param blockIndex 확정된 블록 번호 (accepted가 false면 null)
 * @param reason     처리 결과 코드. 확정이면 OK, 거부면 이유 (리더를 모름 → NOT_LEADER 등)
 */
public record BlockResultDto(boolean accepted, Long blockIndex, ReasonCode reason) {

    /** 예전 형식 (이유 코드 없음) — FairChain 코드 호환용 */
    public BlockResultDto(boolean accepted, Long blockIndex) {
        this(accepted, blockIndex, accepted ? ReasonCode.OK : null);
    }

    public static BlockResultDto rejected(ReasonCode reason) {
        return new BlockResultDto(false, null, reason);
    }
}
