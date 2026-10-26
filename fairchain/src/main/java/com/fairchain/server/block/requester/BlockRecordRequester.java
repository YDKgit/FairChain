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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class BlockRecordRequester implements BlockRecordAccess {

    private static final Logger log = LoggerFactory.getLogger(BlockRecordRequester.class);

    private final List<HttpRequester> nodes;
    /** 노드마다 스레드 하나 — 기록을 3대에 동시에 보낸다 */
    private final ExecutorService submitPool;

    public BlockRecordRequester(List<HttpRequester> nodeRequesters) {
        this.nodes = nodeRequesters;
        this.submitPool = Executors.newFixedThreadPool(Math.max(1, nodeRequesters.size()), r -> {
            Thread t = new Thread(r, "node-submit");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 노드 3대에 같은 기록을 동시에 보낸다 (노드끼리는 통신하지 않는다).
     * 2대 이상이 같은 결과(승인 · 블록 번호)를 돌려주면 그 결과를 채택하고, 아니면 미승인으로 본다.
     * 3대의 응답(또는 제한 시간)을 모두 받은 뒤에 반환하고 대기 줄은 다음 기록을 그 뒤에 보내므로, 노드마다 기록 순서가 같다.
     * 노드는 같은 기록을 다시 받아도 블록을 새로 만들지 않으므로, 미승인일 때 그대로 다시 보내도 된다.
     */
    @Override
    public BlockResultDto submitRecord(BlockRecordDto dto) {
        List<CompletableFuture<BlockResultDto>> calls = nodes.stream()
                .map(node -> CompletableFuture.supplyAsync(() -> submitTo(node, dto), submitPool))
                .toList();
        List<BlockResultDto> responses = calls.stream().map(CompletableFuture::join).toList();
        return Majority.pick(responses, nodes.size()).orElse(new BlockResultDto(false, null));
    }

    private BlockResultDto submitTo(HttpRequester node, BlockRecordDto dto) {
        try {
            return node.post(BlockRecordApi.RECORDS, dto, BlockResultDto.class);
        } catch (RuntimeException e) {
            log.warn("노드 기록 응답 없음: {}", e.getMessage());
            return null;
        }
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
