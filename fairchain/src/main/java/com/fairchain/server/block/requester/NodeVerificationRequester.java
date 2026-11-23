package com.fairchain.server.block.requester;

import com.fairchain.nodeapi.NodeVerificationApi;
import com.fairchain.nodeapi.dto.BlockBatchVerifyRequestDto;
import com.fairchain.nodeapi.dto.BlockBatchVerifyResponseDto;
import com.fairchain.nodeapi.dto.BlockVerifyItemResultDto;
import com.fairchain.server.block.requester.NodeDirectory.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class NodeVerificationRequester implements NodeVerificationAccess {

    private static final Logger log = LoggerFactory.getLogger(NodeVerificationRequester.class);

    private final NodeDirectory directory;
    private final NodeDivergenceReporter reporter;

    public NodeVerificationRequester(NodeDirectory directory, NodeDivergenceReporter reporter) {
        this.directory = directory;
        this.reporter = reporter;
    }

    @Override
    public BlockBatchVerifyResponseDto verifyBatch(BlockBatchVerifyRequestDto dto) {
        List<Node> nodes = directory.nodes();      // 이 요청 동안은 이 목록만 쓴다
        // 노드별 응답을 블록 번호 → 결과로, 그리고 노드별 마지막 블록 번호
        List<Map<Long, BlockVerifyItemResultDto>> perNode = new ArrayList<>();
        List<Long> lastIndex = new ArrayList<>();
        for (Node node : nodes) {
            try {
                BlockBatchVerifyResponseDto response =
                        node.http().post(NodeVerificationApi.VERIFY_BATCH, dto, BlockBatchVerifyResponseDto.class);
                perNode.add(response == null ? Map.of() : response.results().stream()
                        .collect(Collectors.toMap(BlockVerifyItemResultDto::blockIndex, r -> r, (a, b) -> a)));
                lastIndex.add(response == null ? null : response.lastBlockIndex());
            } catch (RuntimeException e) {
                log.warn("노드 묶음 검증 응답 없음: {}", e.getMessage());
                perNode.add(Map.of());
                lastIndex.add(null);
            }
        }

        List<BlockVerifyItemResultDto> results = new ArrayList<>();
        dto.records().forEach(item -> {
            List<BlockVerifyItemResultDto> votes = perNode.stream().map(m -> m.get(item.blockIndex())).toList();
            Optional<BlockVerifyItemResultDto> majority = Majority.pick(votes, nodes.size());
            majority.ifPresent(m -> {
                results.add(m);
                // 다수와 다른 노드: 그 블록 번호까지 갖고 있는데 답이 다르면 신고, 아직 그 번호가 없으면 뒤처짐 (미정사항 13번)
                for (int i = 0; i < votes.size(); i++) {
                    BlockVerifyItemResultDto vote = votes.get(i);
                    Long last = lastIndex.get(i);
                    if (vote != null && !vote.equals(m) && (last == null || item.blockIndex() == null || last >= item.blockIndex())) {
                        reporter.report(nodes, i, item.blockIndex(), item.recordKey());
                    }
                }
            });
        });
        return new BlockBatchVerifyResponseDto(results);
    }
}
