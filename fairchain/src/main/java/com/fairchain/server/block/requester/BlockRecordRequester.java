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

@Component
public class BlockRecordRequester implements BlockRecordAccess {

    private static final Logger log = LoggerFactory.getLogger(BlockRecordRequester.class);

    private final List<HttpRequester> nodes;

    public BlockRecordRequester(List<HttpRequester> nodeRequesters) {
        this.nodes = nodeRequesters;
    }

    /** 노드 하나에 제출하면 그 노드가 다른 노드에 제안 · 합의한다. 실패하면 다음 노드로 */
    @Override
    public BlockResultDto submitRecord(BlockRecordDto dto) {
        for (HttpRequester node : nodes) {
            try {
                BlockResultDto result = node.post(BlockRecordApi.RECORDS, dto, BlockResultDto.class);
                if (result != null && result.accepted()) {
                    return result;
                }
            } catch (RuntimeException e) {
                log.warn("노드 기록 실패, 다음 노드 시도: {}", e.getMessage());
            }
        }
        return new BlockResultDto(false, null);
    }

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
        return Majority.pick(responses, nodes.size())
                .orElseThrow(() -> new FairChainException(ErrorCode.NODE_UNAVAILABLE, "노드 다수 응답을 얻지 못했습니다"));
    }
}
