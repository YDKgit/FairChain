package com.fairchain.node;

import com.fairchain.nodeapi.BlockRecordApi;
import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.MemberInfoDto;
import com.fairchain.nodeapi.dto.MemberJoinDto;
import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 6단계: 팀 가입 · 해지 (미정사항 1 · 15번). 처음 그룹은 A1 · B1 · C1, 새 팀 D의 D1이 노드 관리 API로 들어왔다 나간다.
 */
class MembershipChangeTest {

    private static final String SECRET = "membership-test-secret";
    private static final String RECORD = "b".repeat(64);
    private static final String[] IDS = {"A1", "B1", "C1", "D1"};
    private static final int D1 = 3;

    @TempDir
    Path dir;

    private final String dbName = "members-" + UUID.randomUUID();
    private final int[] ports = new int[4];
    private final ConfigurableApplicationContext[] nodes = new ConfigurableApplicationContext[4];
    private final RestClient http;

    MembershipChangeTest() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(90_000);
        http = RestClient.builder().requestFactory(factory).defaultHeader(NodeHeaders.NODE_SECRET, SECRET).build();
    }

    @BeforeEach
    void startNodes() throws IOException {
        for (int i = 0; i < 4; i++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                ports[i] = socket.getLocalPort();
            }
        }
        for (int i = 0; i < 4; i++) {
            start(i);
        }
    }

    @AfterEach
    void stopNodes() {
        for (int i = 0; i < 4; i++) {
            if (nodes[i] != null) {
                nodes[i].close();
            }
        }
    }

    private void start(int i) {
        // 새 노드도 node.members에는 처음 그룹(A1 · B1 · C1)을 적는다 — MicroRaft에서 새 노드는 처음 멤버 명단으로 만들고
        // 리더가 멤버 변경으로 넣는다. 처음 명단에 없는 노드는 learner로 시작해 선거를 하지 않는다
        nodes[i] = new SpringApplicationBuilder(NodeApplication.class).run(new ArrayList<>(List.of(
                "--server.port=" + ports[i],
                "--spring.datasource.url=jdbc:h2:mem:" + dbName + "-" + i + ";DB_CLOSE_DELAY=-1",
                "--node.id=" + IDS[i],
                "--node.url=" + url(i),
                "--node.members=A1=" + url(0) + ",B1=" + url(1) + ",C1=" + url(2),
                "--node.secret=" + SECRET,
                "--node.key-file=" + dir.resolve(IDS[i] + ".key"),
                "--node.raft.store-file=" + dir.resolve(IDS[i] + "-raft.sqlite"),
                "--spring.main.banner-mode=off",
                "--spring.jmx.enabled=false",
                "--logging.level.root=WARN"))
                .toArray(String[]::new));
    }

    private String url(int i) {
        return "http://127.0.0.1:" + ports[i];
    }

    private long write(int ticket) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (int i = 0; i < 3; i++) {
                try {
                    BlockResultDto r = http.post().uri(url(i) + BlockRecordApi.RECORDS)
                            .body(new BlockRecordDto(String.format("%064x", ticket), 1L, RECORD)).retrieve().body(BlockResultDto.class);
                    if (r != null && r.accepted()) {
                        return r.blockIndex();
                    }
                } catch (Exception ignored) {
                    // 다음 노드
                }
            }
            sleep(300);
        }
        throw new AssertionError("기록되지 않음: " + ticket);
    }

    private NodeStatusDto status(int i) {
        return http.get().uri(url(i) + NodeAdminApi.STATUS).retrieve().body(NodeStatusDto.class);
    }

    private NodeLedgerStatusDto ledger(int i) {
        return http.get().uri(url(i) + NodeAuditApi.LEDGER_STATUS).retrieve().body(NodeLedgerStatusDto.class);
    }

    private int leader() {
        int[] found = {-1};
        await("리더", () -> {
            for (int i = 0; i < 3; i++) {
                if ("LEADER".equals(status(i).raftRole())) {
                    found[0] = i;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    private static List<String> votingIds(NodeStatusDto s) {
        return s.members().stream().filter(MemberInfoDto::voting).map(MemberInfoDto::id).sorted().toList();
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (Exception ignored) {
                // 아직
            }
            sleep(150);
        }
        throw new AssertionError("20초 안에 충족되지 않음: " + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void aFourthTeamJoinsAsLearnerIsPromotedAndLeavesAgain() {
        int leader = leader();
        for (int t = 1; t <= 3; t++) {
            assertEquals((long) t, write(t));
        }
        assertEquals("LEARNER", status(D1).raftRole(), "처음 명단에 없는 노드는 learner로 기다린다");
        assertEquals(0L, ledger(D1).height());

        // 팔로워에게 보내도 리더에게 넘어간다. 공개키는 리더가 D1의 상태 조회에서 받는다
        MembershipResultDto join = http.post().uri(url((leader + 1) % 3) + NodeAdminApi.MEMBERS)
                .body(new MemberJoinDto("D1", "D", url(D1), null)).retrieve().body(MembershipResultDto.class);
        assertTrue(join.accepted(), join.message());

        await("D1이 투표 멤버", () -> votingIds(status(leader)).equals(List.of("A1", "B1", "C1", "D1"))
                && "FOLLOWER".equals(status(D1).raftRole()));
        assertEquals(status(D1).publicKey(), status(leader).members().stream()
                .filter(m -> m.id().equals("D1")).findFirst().orElseThrow().publicKey(), "가입할 때 공개키도 Raft 로그에");
        assertEquals("D", status(D1).team());
        assertEquals(4L, write(4));
        await("D1도 4번 블록 (자기 서명)", () -> ledger(D1).height() == 4
                && ledger(D1).headHash().equals(ledger(leader).headHash()));
        for (BlockDto b : http.get().uri(url(D1) + NodeAuditApi.BLOCKS + "?from=1&limit=10").retrieve().body(BlockDto[].class)) {
            assertEquals("D1", b.signerId());
        }

        MembershipResultDto again = http.post().uri(url(leader) + NodeAdminApi.MEMBERS)
                .body(new MemberJoinDto("D1", "D", url(D1), null)).retrieve().body(MembershipResultDto.class);
        assertTrue(again.accepted(), "이미 멤버면 그대로");

        // 해지 4팀 → 3팀
        MembershipResultDto leave = http.delete().uri(url(leader) + NodeAdminApi.memberPath("D1"))
                .retrieve().body(MembershipResultDto.class);
        assertTrue(leave.accepted(), leave.message());
        await("D1이 명단에서 빠짐", () -> votingIds(status(leader)).equals(List.of("A1", "B1", "C1"))
                && status(leader).members().stream().noneMatch(m -> m.id().equals("D1")));
        assertEquals(5L, write(5), "3팀으로 계속 기록");

        // 3팀 → 2팀은 거부
        MembershipResultDto tooFew = http.delete().uri(url((leader + 1) % 3) + NodeAdminApi.memberPath(IDS[(leader + 2) % 3]))
                .retrieve().body(MembershipResultDto.class);
        assertFalse(tooFew.accepted());
        assertTrue(tooFew.message().contains("3개 미만"), tooFew.message());
        assertEquals(List.of("A1", "B1", "C1"), votingIds(status(leader)));
    }

    @Test
    void aJoinWithoutAReachableNodeIsRefused() {
        int leader = leader();
        MembershipResultDto r = http.post().uri(url(leader) + NodeAdminApi.MEMBERS)
                .body(new MemberJoinDto("E1", "E", "http://127.0.0.1:1", null)).retrieve().body(MembershipResultDto.class);
        assertFalse(r.accepted());
        assertEquals(3, votingIds(status(leader)).size());
    }
}
