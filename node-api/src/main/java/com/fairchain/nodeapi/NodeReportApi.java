package com.fairchain.nodeapi;

import com.fairchain.nodeapi.dto.DivergenceReportDto;
import com.fairchain.nodeapi.dto.ReportAckDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 불일치 신고 (미정사항 14번) — NodeController가 구현.
 * 리더 층 리더가 받는다. 다른 노드가 받으면 리더에게 넘긴다. FairChain은 신고만 하고 판정하지 않는다.
 */
public interface NodeReportApi {

    String REPORTS = "/node/consensus/reports";

    @PostMapping(REPORTS)
    ReportAckDto report(@RequestBody DivergenceReportDto report);
}
