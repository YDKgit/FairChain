package com.fairchain.server.block.requester;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.nodeapi.BlockRecordApi;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.server.common.ErrorCode;
import com.fairchain.server.common.FairChainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 노드끼리는 통신하지 않는다. FairChain이 기록을 노드 3대에 동시에 보내고, 같은 블록(번호 · 해시)을 돌려준 노드가
 * 2대 이상이면 확정한다. 기록 순서는 FairChain 대기 줄이 정하고, 빠진 기록은 FairChain이 확정 순서대로 다시 보낸다.
 */
@Component
public class BlockRecordRequester implements BlockRecordAccess {

    private static final Logger log = LoggerFactory.getLogger(BlockRecordRequester.class);
    /** 따라잡을 때 한 번에 읽어 오는 확정 기록 수 */
    private static final int CATCH_UP_PAGE = 500;

    private final List<HttpRequester> nodes;
    private final ConfirmedLedger ledger;
    /** 노드마다 스레드 하나 — 기록을 3대에 동시에 보낸다 */
    private final ExecutorService pool;
    /** 노드별로 확인된 체인 높이. 없으면 다음 기록 전에 노드에 직접 물어본다 */
    private final Map<HttpRequester, Long> knownHeight = new ConcurrentHashMap<>();
    /** 응답이 없었거나 체인이 갈라진 노드 — 다음 주기 점검까지 기록을 보내지 않는다 (제한 시간만큼 기다리지 않도록) */
    private final Set<HttpRequester> sidelined = ConcurrentHashMap.newKeySet();
    /** 가장 최근에 다수가 확정한 블록 — 높이는 같은데 마지막 블록이 다른 노드를 걸러낸다 */
    private volatile BlockResultDto lastConfirmed;

    public BlockRecordRequester(List<HttpRequester> nodeRequesters, ConfirmedLedger ledger) {
        this.nodes = nodeRequesters;
        this.ledger = ledger;
        this.pool = Executors.newFixedThreadPool(Math.max(1, nodeRequesters.size()), r -> {
            Thread t = new Thread(r, "node-submit");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 노드 3대에 같은 기록을 동시에 보낸다. 뒤처진 노드에는 먼저 빠진 기록을 확정 순서대로 보낸다.
     * 3대의 응답(또는 제한 시간)을 모두 받은 뒤에 반환하고 대기 줄은 다음 기록을 그 뒤에 보내므로, 노드마다 기록 순서가 같다.
     * 노드는 같은 기록을 다시 받아도 블록을 새로 만들지 않으므로, 미승인일 때 그대로 다시 보내도 된다.
     */
    @Override
    public synchronized BlockResultDto submitRecord(BlockRecordDto dto) {
        long confirmed = ledger.height();
        List<CompletableFuture<BlockResultDto>> calls = nodes.stream()
                .map(node -> CompletableFuture.supplyAsync(() -> catchUpAndSubmit(node, confirmed, dto), pool))
                .toList();
        List<BlockResultDto> responses = calls.stream().map(CompletableFuture::join).toList();
        Optional<BlockResultDto> majority = Majority.pick(responses, nodes.size());
        majority.filter(BlockResultDto::accepted).ifPresent(r -> lastConfirmed = r);
        for (int i = 0; i < nodes.size(); i++) {
            if (responses.get(i) != null && !responses.get(i).equals(majority.orElse(null))) {
                knownHeight.remove(nodes.get(i));   // 다수와 다른 블록 — 다음 기록 전에 노드에 다시 묻는다
            }
        }
        return majority.orElse(new BlockResultDto(false, null, null));
    }

    private BlockResultDto catchUpAndSubmit(HttpRequester node, long confirmed, BlockRecordDto dto) {
        if (sidelined.contains(node)) {
            return null;
        }
        try {
            if (!catchUp(node, confirmed)) {
                sidelined.add(node);
                return null;
            }
            BlockResultDto result = node.post(BlockRecordApi.RECORDS, dto, BlockResultDto.class);
            if (result != null && result.accepted() && result.blockIndex() != null) {
                knownHeight.merge(node, result.blockIndex(), Math::max);
            }
            return result;
        } catch (RuntimeException e) {
            knownHeight.remove(node);
            sidelined.add(node);
            log.warn("노드 기록 응답 없음 — 다음 점검까지 제외: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 노드 체인을 FairChain이 확정한 높이까지 맞춘다 — 빠진 기록을 확정 순서대로 다시 보낸다.
     * 맞출 수 없는 노드(체인 깨짐, FairChain보다 긴 체인, 같은 높이인데 마지막 블록이 다름, 다시 보낸 기록의 번호가 어긋남)는 false.
     */
    private boolean catchUp(HttpRequester node, long confirmed) {
        Long known = knownHeight.get(node);
        long height;
        String head = null;
        if (known != null) {
            height = known;
        } else {
            NodeLedgerStatusDto status = node.get(NodeAuditApi.LEDGER_STATUS, NodeLedgerStatusDto.class);
            if (!status.chainValid()) {
                log.warn("노드 체인이 깨져 있어 투표에서 뺍니다");
                return false;
            }
            height = status.height();
            head = status.headHash();
        }
        if (height > confirmed + 1) {   // +1: 확정 직전인 기록(지금 보내는 기록)을 이미 가진 경우
            log.warn("노드 체인({})이 FairChain 확정({})보다 깁니다 — 투표에서 뺍니다", height, confirmed);
            return false;
        }
        BlockResultDto last = lastConfirmed;
        if (head != null && last != null && height == last.blockIndex() && !last.blockHash().equals(head)) {
            log.warn("노드의 블록 {}이 다수가 확정한 블록과 다릅니다 — 투표에서 뺍니다", height);
            return false;
        }
        for (long from = height + 1; from <= confirmed; from += CATCH_UP_PAGE) {
            long to = Math.min(confirmed, from + CATCH_UP_PAGE - 1);
            List<ConfirmedLedger.ConfirmedBlock> missed = ledger.between(from, to);
            if (missed.size() != to - from + 1) {
                log.warn("확정 기록 {}~{} 중 빠진 번호가 있어 따라잡을 수 없습니다", from, to);
                return false;
            }
            for (ConfirmedLedger.ConfirmedBlock block : missed) {
                BlockResultDto r = node.post(BlockRecordApi.RECORDS, block.record(), BlockResultDto.class);
                if (r == null || !r.accepted() || r.blockIndex() == null || r.blockIndex() != block.blockIndex()) {
                    knownHeight.remove(node);
                    log.warn("따라잡기 중 블록 번호가 어긋났습니다 (기대 {}, 응답 {}) — 투표에서 뺍니다", block.blockIndex(), r);
                    return false;
                }
            }
        }
        if (height < confirmed) {
            log.info("노드 따라잡기 완료: 블록 {} → {}", height, confirmed);
        }
        knownHeight.merge(node, Math.max(height, confirmed), Math::max);
        return true;
    }

    /** 주기 점검: 모든 노드에 직접 물어 뒤처진 노드를 따라잡게 하고, 제외했던 노드를 다시 넣는다 */
    @Override
    public synchronized void catchUpNodes() {
        long confirmed = ledger.height();
        List<CompletableFuture<Void>> probes = nodes.stream()
                .map(node -> CompletableFuture.runAsync(() -> {
                    knownHeight.remove(node);
                    try {
                        if (catchUp(node, confirmed)) {
                            sidelined.remove(node);
                        } else {
                            sidelined.add(node);
                        }
                    } catch (RuntimeException e) {
                        knownHeight.remove(node);
                        sidelined.add(node);
                    }
                }, pool))
                .toList();
        probes.forEach(CompletableFuture::join);
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
