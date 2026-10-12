package com.fairchain.server.block.requester;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.nodeapi.NodeVerificationApi;
import com.fairchain.nodeapi.dto.BlockBatchVerifyRequestDto;
import com.fairchain.nodeapi.dto.BlockBatchVerifyResponseDto;
import com.fairchain.nodeapi.dto.BlockVerifyItemResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class NodeVerificationRequester implements NodeVerificationAccess {

    private static final Logger log = LoggerFactory.getLogger(NodeVerificationRequester.class);

    private final List<HttpRequester> nodes;

    public NodeVerificationRequester(List<HttpRequester> nodeRequesters) {
        this.nodes = nodeRequesters;
    }

    @Override
    public BlockBatchVerifyResponseDto verifyBatch(BlockBatchVerifyRequestDto dto) {
        // 노드별 응답을 블록 번호 → 결과로
        List<Map<Long, BlockVerifyItemResultDto>> perNode = new ArrayList<>();
        for (HttpRequester node : nodes) {
            try {
                BlockBatchVerifyResponseDto response =
                        node.post(NodeVerificationApi.VERIFY_BATCH, dto, BlockBatchVerifyResponseDto.class);
                perNode.add(response == null ? Map.of() : response.results().stream()
                        .collect(Collectors.toMap(BlockVerifyItemResultDto::blockIndex, r -> r, (a, b) -> a)));
            } catch (RuntimeException e) {
                log.warn("노드 묶음 검증 응답 없음: {}", e.getMessage());
                perNode.add(Map.of());
            }
        }

        List<BlockVerifyItemResultDto> results = new ArrayList<>();
        dto.records().forEach(item -> {
            List<BlockVerifyItemResultDto> votes = perNode.stream().map(m -> m.get(item.blockIndex())).toList();
            Majority.pick(votes, nodes.size()).ifPresent(results::add);
        });
        return new BlockBatchVerifyResponseDto(results);
    }
}
