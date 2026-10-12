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
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 노드 3대(각자 포트 · 각자 DB)를 띄워 HTTP로만 확인하는 통합 테스트 */
class NodeClusterTest {

    private static final String SECRET = "cluster-test-secret";
    private static final String RECORD = "b".repeat(64);

    private final String dbName = "cluster-" + UUID.randomUUID();
    private final int[] ports = new int[3];
    private final ConfigurableApplicationContext[] nodes = new ConfigurableApplicationContext[3];
    private final RestClient http = RestClient.builder().defaultHeader(NodeHeaders.NODE_SECRET, SECRET).build();

    @BeforeEach
    void startCluster() throws IOException {
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
    void stopCluster() {
        for (int i = 0; i < 3; i++) {
            stop(i);
        }
    }

    private void start(int i) {
        String peers = url((i + 1) % 3) + "," + url((i + 2) % 3);
        nodes[i] = new SpringApplicationBuilder(NodeApplication.class).run(
                "--server.port=" + ports[i],
                // 메모리 DB를 노드마다 따로 두고, 노드를 내렸다 올려도 내용이 남게 한다
                "--spring.datasource.url=jdbc:h2:mem:" + dbName + "-" + i + ";DB_CLOSE_DELAY=-1",
                "--node.peers=" + peers,
                "--node.secret=" + SECRET,
                "--node.sync-initial-delay-ms=300",
                "--node.sync-interval-ms=300",
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

    private BlockResultDto submit(int node, int ticket, long version) {
        return http.post().uri(url(node) + BlockRecordApi.RECORDS)
                .body(new BlockRecordDto(key(ticket), version, RECORD))
                .retrieve().body(BlockResultDto.class);
    }

    private boolean verified(int node, int ticket, long version) {
        return http.post().uri(url(node) + BlockRecordApi.VERIFY)
                .body(new BlockVerifyDto(key(ticket), version, RECORD))
                .retrieve().body(BlockVerifyResultDto.class).valid();
    }

    private NodeLedgerStatusDto status(int node) {
        return http.get().uri(url(node) + NodeAuditApi.LEDGER_STATUS).retrieve().body(NodeLedgerStatusDto.class);
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("10초 안에 충족되지 않음: " + what);
    }

    private boolean sameHeadEverywhere(long height) {
        NodeLedgerStatusDto first = status(0);
        for (int i = 0; i < 3; i++) {
            NodeLedgerStatusDto s = status(i);
            if (s.height() != height || !s.headHash().equals(first.headHash())
                    || !s.chainValid() || !s.pendingIndices().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Test
    void writesContinueWithOneNodeDownAndTheNodeCatchesUpAfterRestart() {
        assertTrue(submit(0, 1, 1).accepted());
        await("3대 모두 1번 블록 확정", () -> sameHeadEverywhere(1));

        stop(2);
        assertEquals(2L, submit(0, 2, 1).blockIndex());
        assertEquals(3L, submit(0, 3, 1).blockIndex());
        assertEquals(4L, submit(1, 4, 1).blockIndex(), "다른 노드가 제안해도 이어서 기록된다");
        assertTrue(status(0).pendingIndices().isEmpty());
        assertTrue(status(1).pendingIndices().isEmpty());

        start(2);
        await("다시 켠 노드가 4번 블록까지 따라잡음", () -> sameHeadEverywhere(4));
        assertTrue(verified(2, 4, 1));

        // 같은 기록을 다른 노드에 다시 보내도 새 블록이 생기지 않는다
        assertEquals(1L, submit(1, 1, 1).blockIndex());
        assertEquals(5L, submit(2, 5, 1).blockIndex(), "따라잡은 노드도 제안할 수 있다");
        await("3대 모두 5번 블록 확정", () -> sameHeadEverywhere(5));
    }

    @Test
    void tamperedNodeIsLeftOutOfVotingWhileTheOtherTwoKeepWorking() {
        assertTrue(submit(0, 1, 1).accepted());
        await("3대 모두 1번 블록 확정", () -> sameHeadEverywhere(1));

        new JdbcTemplate(nodes[1].getBean(DataSource.class))
                .update("update block set record_hash = ? where block_index = 1", "e".repeat(64));

        assertFalse(status(1).chainValid());
        assertFalse(verified(1, 1, 1));

        assertEquals(2L, submit(0, 2, 1).blockIndex(), "나머지 2대로 과반");
        assertTrue(verified(0, 2, 1));
        assertTrue(verified(2, 2, 1));
        // 조작된 노드는 찬성하지 않았으므로 새 블록도, 남은 표도 없다
        assertEquals(1L, status(1).height());
        assertTrue(status(1).pendingIndices().isEmpty());
        assertFalse(submit(1, 3, 1).accepted(), "조작된 노드는 제안도 하지 않는다");
    }

    @Test
    void requestWithoutTheNodeSecretIsRejected() {
        HttpClientErrorException error = assertThrows(HttpClientErrorException.class,
                () -> RestClient.create().get().uri(url(0) + NodeAuditApi.LEDGER_STATUS).retrieve().toBodilessEntity());
        assertEquals(401, error.getStatusCode().value());
    }
}
