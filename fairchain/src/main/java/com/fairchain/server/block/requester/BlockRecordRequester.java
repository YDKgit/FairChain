package com.fairchain.server.block.requester;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.nodeapi.BlockRecordApi;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import com.fairchain.server.common.ErrorCode;
import com.fairchain.server.common.FairChainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class BlockRecordRequester implements BlockRecordAccess {

    private static final Logger log = LoggerFactory.getLogger(BlockRecordRequester.class);

    private final List<HttpRequester> nodes;
    private final NodeDivergenceReporter reporter;

    public BlockRecordRequester(List<HttpRequester> nodeRequesters, NodeDivergenceReporter reporter) {
        this.nodes = nodeRequesters;
        this.reporter = reporter;
    }

    /**
     * 아무 노드에나 제출하면 된다 — 팔로워는 Raft 리더에게 넘기고 리더가 블록을 만들어 과반 저장 시 확정한다.
     * 받지 못하면(리더 선거 중 · 노드 꺼짐 등) 다음 노드로
     */
    @Override
    public BlockResultDto submitRecord(BlockRecordDto dto) {
        for (HttpRequester node : nodes) {
            try {
                BlockResultDto result = node.post(BlockRecordApi.RECORDS, dto, BlockResultDto.class);
                if (result != null && result.accepted()) {
                    return result;
                }
                log.debug("노드가 기록을 받지 않음 ({}), 다음 노드 시도", result == null ? null : result.reason());
            } catch (RuntimeException e) {
                log.warn("노드 기록 실패, 다음 노드 시도: {}", e.getMessage());
            }
        }
        return new BlockResultDto(false, null);
    }

    /**
     * 노드 3대에 묻고 2/3이 같은 답이면 채택. 투표는 답의 내용(일치 여부 · 버전 · 지문)만 비교한다 — 노드마다 마지막 블록 번호는
     * 기록이 오가는 동안 잠깐 다를 수 있기 때문이다. 다수와 다른 답을 한 노드는 번호가 더 작으면 뒤처짐(BEHIND)이라 그냥 두고,
     * 아니면 리더 층에 신고한다 (미정사항 13번).
     */
    @Override
    public BlockVerifyResultDto verifyRecord(BlockVerifyDto dto) {
        List<BlockVerifyResultDto> responses = new ArrayList<>();
        for (HttpRequester node : nodes) {
            try {
                responses.add(node.post(BlockRecordApi.VERIFY, dto, BlockVerifyResultDto.class));
            } catch (RuntimeException e) {
                log.warn("노드 검증 응답 없음: {}", e.getMessage());
                responses.add(null);
            }
        }
        List<BlockVerifyResultDto> votes = responses.stream().map(BlockRecordRequester::content).toList();
        Optional<BlockVerifyResultDto> majority = Majority.pick(votes, nodes.size());
        majority.ifPresent(m -> reportMinority(responses, votes, m, dto.recordKey()));
        return majority.orElseThrow(() -> new FairChainException(ErrorCode.NODE_UNAVAILABLE, "노드 다수 응답을 얻지 못했습니다"));
    }

    /** 투표에 쓰는 부분 — 마지막 블록 번호는 뺀다 */
    private static BlockVerifyResultDto content(BlockVerifyResultDto r) {
        return r == null ? null : new BlockVerifyResultDto(r.valid(), r.latestVersion(), r.latestRecordHash());
    }

    private void reportMinority(List<BlockVerifyResultDto> responses, List<BlockVerifyResultDto> votes,
                                BlockVerifyResultDto majority, String recordKey) {
        long top = 0;
        for (int i = 0; i < votes.size(); i++) {
            if (majority.equals(votes.get(i)) && responses.get(i).lastBlockIndex() != null) {
                top = Math.max(top, responses.get(i).lastBlockIndex());
            }
        }
        for (int i = 0; i < votes.size(); i++) {
            BlockVerifyResultDto vote = votes.get(i);
            if (vote == null || vote.equals(majority)) {
                continue;
            }
            Long last = responses.get(i).lastBlockIndex();
            if (last != null && last < top) {
                log.debug("노드 {}는 뒤처짐 ({} < {}) — 신고하지 않음", i + 1, last, top);
                continue;
            }
            reporter.report(i, null, recordKey);
        }
    }
}
