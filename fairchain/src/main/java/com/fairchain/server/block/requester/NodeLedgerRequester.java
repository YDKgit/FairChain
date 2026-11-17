package com.fairchain.server.block.requester;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.server.common.config.FairChainProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class NodeLedgerRequester implements NodeLedgerAccess {

    private final List<HttpRequester> nodes;
    private final List<String> urls;

    public NodeLedgerRequester(List<HttpRequester> nodeRequesters, FairChainProperties properties) {
        this.nodes = nodeRequesters;
        this.urls = properties.nodes().urls();
    }

    @Override
    public List<NodeLedger> ledgers() {
        List<NodeLedger> result = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            int number = i + 1;
            String url = i < urls.size() ? urls.get(i) : "node" + number;
            try {
                NodeLedgerStatusDto status = nodes.get(i).get(NodeAuditApi.LEDGER_STATUS, NodeLedgerStatusDto.class);
                result.add(new NodeLedger(number, url, true, status, null));
            } catch (RuntimeException e) {
                result.add(new NodeLedger(number, url, false, null, e.getMessage()));
            }
        }
        return result;
    }

    @Override
    public List<BlockDto> blocks(int number, long from, int limit) {
        try {
            BlockDto[] blocks = nodes.get(number - 1)
                    .get(NodeAuditApi.BLOCKS + "?from=" + from + "&limit=" + limit, BlockDto[].class);
            return blocks == null ? List.of() : Arrays.asList(blocks);
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
