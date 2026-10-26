package com.fairchain.node;

import com.fairchain.nodeapi.BlockRecordApi;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 노드 3대(각자 포트 · 각자 DB)를 띄워 HTTP로만 확인하는 통합 테스트.
 * 노드끼리는 서로의 주소도 모른다. FairChain이 하는 것처럼 같은 기록을 3대에 각각 보내고 응답을 비교한다.
 */
class NodeClusterTest {

    private static final String SECRET = "cluster-test-secret";
    private static final String RECORD = "b".repeat(64);

    private final String dbName = "cluster-" + UUID.randomUUID();
    private final int[] ports = new int[3];
    private final ConfigurableApplicationContext[] nodes = new ConfigurableApplicationContext[3];
    private final RestClient http = RestClient.builder().defaultHeader(NodeHeaders.NODE_SECRET, SECRET).build();

    @BeforeEach
    void startNodes() throws IOException {
        for (int i = 0; i < 3; i++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                ports[i] = socket.getLocalPort();
            }
        }
        for (int i = 0; i < 3; i++) {
            start(i);
        }
    }

    @AfterEach
    void stopNodes() {
        for (int i = 0; i < 3; i++) {
            stop(i);
        }
    }

    private void start(int i) {
        nodes[i] = new SpringApplicationBuilder(NodeApplication.class).run(
                "--server.port=" + ports[i],
                // 메모리 DB를 노드마다 따로 두고, 노드를 내렸다 올려도 내용이 남게 한다
                "--spring.datasource.url=jdbc:h2:mem:" + dbName + "-" + i + ";DB_CLOSE_DELAY=-1",
                "--node.secret=" + SECRET,
                "--node.audit-interval-ms=300",
                "--spring.main.banner-mode=off",
                "--spring.jmx.enabled=false",
                "--logging.level.root=WARN");
    }

    private void stop(int i) {
        if (nodes[i] != null) {
            nodes[i].close();
            nodes[i] = null;
        }
    }

    private String url(int i) {
        return "http://127.0.0.1:" + ports[i];
    }

    private static String key(int n) {
        return String.format("%064x", n);
    }

    private BlockResultDto submit(int node, int ticket) {
        return http.post().uri(url(node) + BlockRecordApi.RECORDS)
                .body(new BlockRecordDto(key(ticket), 1L, RECORD))
                .retrieve().body(BlockResultDto.class);
    }

    /** FairChain처럼 켜져 있는 모든 노드에 같은 기록을 보내고 응답을 모은다 */
    private List<BlockResultDto> broadcast(int ticket) {
        List<BlockResultDto> responses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            if (nodes[i] != null) {
                responses.add(submit(i, ticket));
            }
        }
        return responses;
    }

    private boolean verified(int node, int ticket) {
        return http.post().uri(url(node) + BlockRecordApi.VERIFY)
                .body(new BlockVerifyDto(key(ticket), 1L, RECORD))
                .retrieve().body(BlockVerifyResultDto.class).valid();
    }

    private NodeLedgerStatusDto status(int node) {
        return http.get().uri(url(node) + NodeAuditApi.LEDGER_STATUS).retrieve().body(NodeLedgerStatusDto.class);
    }

    @Test
    void sameRecordsInTheSameOrderGiveIdenticalChainsOnEveryNode() {
        for (int ticket = 1; ticket <= 5; ticket++) {
            List<BlockResultDto> responses = broadcast(ticket);
            assertEquals(1, responses.stream().distinct().count(), "3대가 같은 블록(번호 · 해시)으로 승인");
            assertTrue(responses.get(0).accepted());
            assertEquals((long) ticket, responses.get(0).blockIndex());
        }

        NodeLedgerStatusDto first = status(0);
        assertEquals(5L, first.height());
        for (int i = 1; i < 3; i++) {
            assertEquals(first.headHash(), status(i).headHash(), "서로 통신하지 않아도 마지막 블록 해시가 같다");
            assertTrue(status(i).chainValid());
        }

        // 같은 기록을 다시 보내도 새 블록이 생기지 않는다
        List<BlockResultDto> again = broadcast(3);
        assertEquals(1, again.stream().distinct().count());
        assertEquals(3L, again.get(0).blockIndex());
        assertEquals(5L, status(0).height());
    }

    @Test
    void oneNodeDownDoesNotStopTheOtherTwo() {
        broadcast(1);

        stop(2);
        List<BlockResultDto> second = broadcast(2);
        assertEquals(2, second.size());
        assertEquals(second.get(0), second.get(1));
        assertEquals(2L, second.get(0).blockIndex());
        List<BlockResultDto> third = broadcast(3);
        assertEquals(third.get(0), third.get(1));
        assertEquals(3L, third.get(0).blockIndex());
        assertTrue(verified(0, 3));
        assertTrue(verified(1, 3));

        // 다시 켠 노드는 저장해 둔 블록은 그대로지만, 꺼져 있던 동안의 기록은 스스로 받아 오지 않는다
        start(2);
        assertEquals(1L, status(2).height());
        assertFalse(verified(2, 3));

        // 놓친 기록을 같은 순서로 다시 보내면 다른 노드와 같은 체인이 된다
        assertEquals(2L, submit(2, 2).blockIndex());
        assertEquals(3L, submit(2, 3).blockIndex());
        assertEquals(status(0).headHash(), status(2).headHash());
        assertTrue(verified(2, 3));
    }

    @Test
    void tamperedNodeRejectsNewRecordsWhileTheOtherTwoStillAgree() {
        broadcast(1);

        new JdbcTemplate(nodes[1].getBean(DataSource.class))
                .update("update block set record_hash = ? where block_index = 1", "e".repeat(64));

        assertFalse(status(1).chainValid());
        assertFalse(verified(1, 1));
        assertTrue(verified(0, 1));
        assertTrue(verified(2, 1));

        List<BlockResultDto> responses = broadcast(2);
        assertEquals(2L, responses.get(0).blockIndex());
        assertFalse(responses.get(1).accepted(), "체인이 깨진 노드는 새 기록을 받지 않는다");
        assertEquals(responses.get(0), responses.get(2), "정상인 두 노드는 같은 블록");
        assertNotEquals(status(0).headHash(), status(1).headHash());
    }

    @Test
    void requestWithoutTheNodeSecretIsRejected() {
        HttpClientErrorException error = assertThrows(HttpClientErrorException.class,
                () -> RestClient.create().get().uri(url(0) + NodeAuditApi.LEDGER_STATUS).retrieve().toBodilessEntity());
        assertEquals(401, error.getStatusCode().value());
    }
}
