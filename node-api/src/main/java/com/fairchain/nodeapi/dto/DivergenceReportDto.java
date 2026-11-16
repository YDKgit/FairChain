package com.fairchain.nodeapi.dto;

/**
 * 불일치 신고 (미정사항 14번). FairChain이든 팀 리더든 같은 형식으로 리더 층 리더에게 보낸다. 신고만 하고 판정은 리더가 한다.
 *
 * @param suspectId  다른 답을 한 노드 ID
 * @param blockIndex 다르게 보인 블록 번호 (모르면 null)
 * @param recordKey  다르게 보인 좌석 키 (모르면 null)
 * @param reporter   신고한 쪽 (예: "fairchain", 팀 리더 노드 ID)
 * @param suspectUrl 대상 노드 주소 — 팀원처럼 리더 층 명단에 없는 노드일 때만 필요
 */
public record DivergenceReportDto(String suspectId, Long blockIndex, String recordKey, String reporter,
                                  String suspectUrl) {

    /** 리더 층 노드에 대한 신고 (주소는 리더가 명단에서 찾는다) */
    public DivergenceReportDto(String suspectId, Long blockIndex, String recordKey, String reporter) {
        this(suspectId, blockIndex, recordKey, reporter, null);
    }
}
