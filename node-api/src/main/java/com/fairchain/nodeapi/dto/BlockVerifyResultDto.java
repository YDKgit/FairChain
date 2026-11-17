package com.fairchain.nodeapi.dto;

/**
 * @param valid          노드의 최신 기록이 요청한 버전 · 지문과 같으면 true
 * @param lastBlockIndex 이 노드의 마지막 블록 번호. 다수와 다른 답을 한 노드의 번호가 더 작으면 "뒤처짐"(BEHIND)이고
 *                       신고하지 않는다 (미정사항 13번)
 */
public record BlockVerifyResultDto(boolean valid, Long latestVersion, String latestRecordHash, Long lastBlockIndex) {

    /** 예전 형식 (마지막 블록 번호 없음) — FairChain 코드 호환용 */
    public BlockVerifyResultDto(boolean valid, Long latestVersion, String latestRecordHash) {
        this(valid, latestVersion, latestRecordHash, null);
    }
}
