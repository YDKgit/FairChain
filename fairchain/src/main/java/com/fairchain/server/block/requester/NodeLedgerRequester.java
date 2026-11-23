package com.fairchain.server.block.requester;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import com.fairchain.server.block.requester.NodeDirectory.Node;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class NodeLedgerRequester implements NodeLedgerAccess {

    private final NodeDirectory directory;

    public NodeLedgerRequester(NodeDirectory directory) {
        this.directory = directory;
    }

    @Override
    public List<NodeLedger> ledgers() {
        List<Node> nodes = directory.nodes();
        List<NodeLedger> result = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            int number = i + 1;
            Node node = nodes.get(i);
            try {
                NodeLedgerStatusDto status = node.http().get(NodeAuditApi.LEDGER_STATUS, NodeLedgerStatusDto.class);
                NodeStatusDto raft = raftStatus(node.http());
                String id = node.id() != null ? node.id() : raft == null ? null : raft.id();
                result.add(new NodeLedger(number, id, node.url(), true, status, null, raft));
            } catch (RuntimeException e) {
                result.add(new NodeLedger(number, node.id(), node.url(), false, null, e.getMessage(), null));
            }
        }
        return result;
    }

    /** 리더 층 노드의 상태 (예전 노드라 없으면 null) */
    private static NodeStatusDto raftStatus(HttpRequester node) {
        try {
            return node.get(NodeAdminApi.STATUS, NodeStatusDto.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public List<BlockDto> blocks(String url, long from, int limit) {
        Node node = directory.nodes().stream().filter(n -> n.url().equals(url)).findFirst().orElse(null);
        if (node == null) {
            return List.of();       // 그사이 목록에서 빠진 노드
        }
        try {
            BlockDto[] blocks = node.http().get(NodeAuditApi.BLOCKS + "?from=" + from + "&limit=" + limit, BlockDto[].class);
            return blocks == null ? List.of() : Arrays.asList(blocks);
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
