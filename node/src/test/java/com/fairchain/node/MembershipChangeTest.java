package com.fairchain.node;

import com.fairchain.nodeapi.BlockRecordApi;
import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.node.security.JoinProof;
import com.fairchain.node.security.NodeKeys;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.NodeJoinApi;
import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.JoinChallengeDto;
import com.fairchain.nodeapi.dto.JoinProofDto;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 6단계: 팀 가입 · 해지 (미정사항 1 · 15번). 처음 그룹은 A1 · B1 · C1, 새 팀 D의 D1이 노드 관리 API로 들어왔다 나간다.
 */
class MembershipChangeTest {

    private static final String SECRET = "membership-test-secret";
    private static final String RECORD = "b".repeat(64);
    private static final String[] IDS = {"A1", "B1", "C1", "D1", "D9"};
    private static final int D1 = 3;
    /** 해지된 팀 D가 다시 들어올 때 쓰는 새 노드 (새 ID · 새 장부 · 새 키). 필요한 테스트에서만 켠다 */
    private static final int D9 = 4;

    @TempDir
    Path dir;

    private final String dbName = "members-" + UUID.randomUUID();
    private final int[] ports = new int[5];
    private final ConfigurableApplicationContext[] nodes = new ConfigurableApplicationContext[5];
    private final RestClient http;

    MembershipChangeTest() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(90_000);
        http = RestClient.builder().requestFactory(factory).defaultHeader(NodeHeaders.NODE_SECRET, SECRET).build();
    }

    @BeforeEach
    void startNodes() throws IOException {
        for (int i = 0; i < 5; i++) {
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
        for (int i = 0; i < 5; i++) {
            if (nodes[i] != null) {
                nodes[i].close();
            }
        }
    }

    private void start(int i, String... extra) {
        // 새 노드도 node.members에는 처음 그룹(A1 · B1 · C1)을 적는다 — MicroRaft에서 새 노드는 처음 멤버 명단으로 만들고
        // 리더가 멤버 변경으로 넣는다. 처음 명단에 없는 노드는 learner로 시작해 선거를 하지 않는다
        List<String> args = new ArrayList<>(List.of(
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
                "--logging.level.root=WARN"));
        args.addAll(List.of(extra));
        nodes[i] = new SpringApplicationBuilder(NodeApplication.class).run(args.toArray(String[]::new));
    }

    private void restartD1(String... extra) {
        nodes[D1].close();
        start(D1, extra);
    }

    private MembershipResultDto join(int via, MemberJoinDto dto) {
        return http.post().uri(url(via) + NodeAdminApi.MEMBERS).body(dto).retrieve().body(MembershipResultDto.class);
    }

    /** 비밀값 헤더를 붙이지 않는 클라이언트 — 밖에서 온 요청처럼 */
    private static RestClient stranger() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 가짜 노드: 가입 전 확인에 answer가 만든 답을 돌려주고, 받은 요청마다 비밀값 헤더가 있었는지 적어 둔다.
     * 리더가 확인되지 않은 주소에 비밀값을 보내지 않는지 보려는 것이다
     */
    private static final class FakeNode implements AutoCloseable {
        final HttpServer server;
        final List<String> secretsSeen = new CopyOnWriteArrayList<>();
        final List<String> paths = new CopyOnWriteArrayList<>();

        FakeNode(Function<String, JoinProofDto> answer) throws IOException {
            ObjectMapper json = new ObjectMapper();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                paths.add(exchange.getRequestURI().getPath());
                String secret = exchange.getRequestHeaders().getFirst(NodeHeaders.NODE_SECRET);
                if (secret != null) {
                    secretsSeen.add(secret);
                }
                byte[] body = exchange.getRequestBody().readAllBytes();
                byte[] out = new byte[0];
                int code = 404;
                if (NodeJoinApi.PROOF.equals(exchange.getRequestURI().getPath())) {
                    out = json.writeValueAsBytes(answer.apply(json.readTree(body).path("nonce").asText()));
                    code = 200;
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    exchange.getResponseBody().write(out);
                }
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
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

        // 운영자는 주소만 알려 준다. 팔로워에게 보내도 리더에게 넘어가고, ID · 팀 · 공개키는 리더가 D1에게 직접 확인해 받는다
        MembershipResultDto join = join((leader + 1) % 3, new MemberJoinDto(null, null, url(D1) + "/", null));
        assertTrue(join.accepted(), join.message());
        assertTrue(join.message().contains("D1") && join.message().contains("팀 D"), join.message());

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

        // ID · 팀을 같이 적으면 노드가 답한 값과 같은지 본다
        assertTrue(join(leader, new MemberJoinDto("D1", "D", url(D1), null)).accepted(), "이미 멤버면 그대로");
        MembershipResultDto wrongId = join(leader, new MemberJoinDto("E1", "E", url(D1), null));
        assertFalse(wrongId.accepted());
        assertTrue(wrongId.message().contains("D1"), wrongId.message());

        // 해지 4팀 → 3팀
        MembershipResultDto leave = http.delete().uri(url(leader) + NodeAdminApi.memberPath("D1"))
                .retrieve().body(MembershipResultDto.class);
        assertTrue(leave.accepted(), leave.message());
        await("D1이 명단에서 빠짐", () -> votingIds(status(leader)).equals(List.of("A1", "B1", "C1"))
                && status(leader).members().stream().noneMatch(m -> m.id().equals("D1")));
        assertEquals(5L, write(5), "3팀으로 계속 기록");

        // 해지된 노드는 꺼지지 않고 남아 있어도 스스로 Raft를 멈춘다. 예전에는 해지된 줄 모르고 계속 선거를 걸어 임기 번호가
        // 끝없이 오르고, 남은 멤버가 그 임기를 받아들이면 리더가 물러났다
        await("D1이 해지된 것을 알고 멈춤", () -> "NONE".equals(status(D1).raftRole())
                && status(D1).outReason() != null && status(D1).outReason().contains("해지"));
        int termAfterLeave = status(leader).term();
        sleep(7_000);       // 하트비트 시간 제한(4초)보다 길게
        assertEquals("LEADER", status(leader).raftRole(), "해지된 노드 때문에 리더가 바뀌지 않는다");
        assertEquals(termAfterLeave, status(leader).term(), "해지된 노드가 임기 번호를 올리지 않는다");

        // 해지된 ID는 다시 쓰지 않는다 (etcd의 멤버 ID와 같은 규칙) — 받아 주면 그 노드는 로그의 해지 기록에서 멈춰 죽은 멤버가 된다
        MembershipResultDto reused = join(leader, new MemberJoinDto(null, null, url(D1), null));
        assertFalse(reused.accepted());
        assertTrue(reused.message().contains("해지된"), reused.message());
        assertTrue(status(leader).members().stream().noneMatch(m -> m.id().equals("D1")), "learner로도 남지 않는다");

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
        MembershipResultDto r = join(leader, new MemberJoinDto("E1", "E", "http://127.0.0.1:1", null));
        assertFalse(r.accepted());
        assertFalse(join(leader, new MemberJoinDto(null, null, "192.168.0.14:9001", null)).accepted(), "스킴 없는 주소");
        assertFalse(join(leader, new MemberJoinDto(null, null, "http://user:pw@127.0.0.1:9", null)).accepted());
        assertEquals(3, votingIds(status(leader)).size());
    }

    /**
     * 가짜 노드를 붙일 수 없다: 주소만 안다고 되는 게 아니라 그 주소의 노드가 공유 비밀값을 알고 자기 개인키를 갖고 있어야 한다.
     * 그리고 리더는 확인이 끝나기 전에는 그 주소에 비밀값을 보내지 않는다 (예전에는 상태 조회에 비밀값 헤더를 붙여 보냈다).
     */
    @Test
    void aNodeThatCannotProveItselfIsRefusedAndNeverGetsTheSecret() throws IOException {
        int leader = leader();
        NodeKeys fakeKeys = new NodeKeys("X1", dir.resolve("fake.key"));

        // 1) 자기 키는 있지만 비밀값을 모르는 노드
        try (FakeNode fake = new FakeNode(nonce -> {
            String fakeUrl = "http://127.0.0.1:0";
            byte[] payload = JoinProof.payload(nonce, "X1", "X", "leader-layer", fakeUrl, fakeKeys.publicKey());
            return new JoinProofDto("X1", "X", "leader-layer", fakeUrl, fakeKeys.publicKey(),
                    JoinProof.mac("guessed-secret", payload), fakeKeys.signBytes(payload));
        })) {
            MembershipResultDto r = join(leader, new MemberJoinDto(null, null, fake.url(), null));
            assertFalse(r.accepted());
            assertTrue(r.message().contains("비밀값"), r.message());
            assertEquals(List.of(NodeJoinApi.PROOF), fake.paths, "확인 요청 말고는 아무것도 보내지 않는다");
            assertTrue(fake.secretsSeen.isEmpty(), "확인되지 않은 주소에 비밀값을 보냈다");
        }

        // 2) 진짜 노드 D1의 답을 받아다 그대로 전달하는 주소 — 증명은 맞지만 D1이 아는 자기 주소와 다르다
        RestClient stranger = stranger();
        try (FakeNode relay = new FakeNode(nonce -> stranger.post().uri(url(D1) + NodeJoinApi.PROOF)
                .body(new JoinChallengeDto(nonce)).retrieve().body(JoinProofDto.class))) {
            MembershipResultDto r = join(leader, new MemberJoinDto(null, null, relay.url(), null));
            assertFalse(r.accepted());
            assertTrue(r.message().contains("주소"), r.message());
            assertTrue(relay.secretsSeen.isEmpty(), "전달만 하는 주소에 비밀값을 보냈다");
        }

        // 3) 남의 공개키를 자기 것이라고 하는 노드 (비밀값이 새어 나간 경우에도 개인키가 없으면 안 된다)
        String d1Key = status(D1).publicKey();
        FakeNode[] self = new FakeNode[1];
        try (FakeNode thief = new FakeNode(nonce -> {
            byte[] payload = JoinProof.payload(nonce, "D1", "D", "leader-layer", self[0].url(), d1Key);
            return new JoinProofDto("D1", "D", "leader-layer", self[0].url(), d1Key,
                    JoinProof.mac(SECRET, payload), fakeKeys.signBytes(payload));
        })) {
            self[0] = thief;
            MembershipResultDto r = join(leader, new MemberJoinDto(null, null, thief.url(), null));
            assertFalse(r.accepted());
            assertTrue(r.message().contains("개인키"), r.message());
        }

        assertEquals(List.of("A1", "B1", "C1"), votingIds(status(leader)));
        assertTrue(status(leader).members().stream().noneMatch(m -> m.id().equals("X1")));

        // 가입 전 확인만 비밀값 없이 받는다. 나머지 /node/** 는 그대로 401
        JoinProofDto proof = stranger.post().uri(url(D1) + NodeJoinApi.PROOF)
                .body(new JoinChallengeDto(JoinProof.newNonce())).retrieve().body(JoinProofDto.class);
        assertEquals("D1", proof.id());
        assertEquals(url(D1), proof.url());
        assertEquals(401, statusOf(() -> stranger.get().uri(url(D1) + NodeAdminApi.STATUS).retrieve().toBodilessEntity()));
        assertEquals(401, statusOf(() -> stranger.post().uri(url(leader) + NodeAdminApi.MEMBERS)
                .body(new MemberJoinDto(null, null, url(D1), null)).retrieve().toBodilessEntity()));
        assertEquals(401, statusOf(() -> stranger.post().uri(url(D1) + NodeJoinApi.PROOF + "/../admin/status")
                .body(new JoinChallengeDto(JoinProof.newNonce())).retrieve().toBodilessEntity()));
        assertEquals(400, statusOf(() -> stranger.post().uri(url(D1) + NodeJoinApi.PROOF)
                .body(new JoinChallengeDto("short")).retrieve().toBodilessEntity()));
    }

    private static int statusOf(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            return e.getStatusCode().value();
        }
    }

    /** 해지된 팀은 새 노드(새 ID)로 다시 들어온다. 새 노드가 따라가는 로그에는 예전 노드의 가입 · 해지 기록이 그대로 있다 */
    @Test
    void aRemovedTeamComesBackUnderANewNodeId() {
        int leader = leader();
        for (int t = 1; t <= 3; t++) {
            assertEquals((long) t, write(t));
        }
        assertTrue(join(leader, new MemberJoinDto(null, null, url(D1), null)).accepted());
        await("D1이 투표 멤버", () -> votingIds(status(leader)).equals(List.of("A1", "B1", "C1", "D1")));
        MembershipResultDto leave = http.delete().uri(url(leader) + NodeAdminApi.memberPath("D1"))
                .retrieve().body(MembershipResultDto.class);
        assertTrue(leave.accepted(), leave.message());
        await("D1이 명단에서 빠짐", () -> votingIds(status(leader)).equals(List.of("A1", "B1", "C1")));

        start(D9);
        MembershipResultDto back = join(leader, new MemberJoinDto(null, null, url(D9), null));
        assertTrue(back.accepted(), back.message());
        await("D9가 투표 멤버", () -> votingIds(status(leader)).equals(List.of("A1", "B1", "C1", "D9"))
                && "FOLLOWER".equals(status(D9).raftRole()));
        assertEquals("D", status(D9).team());
        assertEquals(4L, write(4));
        await("D9도 4번 블록까지 같은 체인", () -> ledger(D9).height() == 4
                && ledger(D9).headHash().equals(ledger(leader).headHash()));
        assertTrue(status(D9).outReason() == null, "예전 노드 D1의 해지 기록은 D9와 상관없다");
    }

    /**
     * 꺼져 있는 동안 해지된 노드: 자기 해지 기록을 받지 못해, 다시 켜지면 아직 멤버인 줄 안다. 남은 멤버가 그 노드의 Raft 메시지에
     * 410으로 답해 알려 주고(etcd도 해지된 멤버의 연결을 거절한다), 그 노드는 멈춘다. 그룹은 흔들리지 않는다
     */
    @Test
    void aNodeRemovedWhileItWasDownStopsWhenItComesBack() {
        int leader = leader();
        assertEquals(1L, write(1));
        assertTrue(join(leader, new MemberJoinDto(null, null, url(D1), null)).accepted());
        await("D1이 투표 멤버", () -> votingIds(status(leader)).equals(List.of("A1", "B1", "C1", "D1"))
                && "FOLLOWER".equals(status(D1).raftRole()));

        nodes[D1].close();
        nodes[D1] = null;
        MembershipResultDto leave = http.delete().uri(url(leader) + NodeAdminApi.memberPath("D1"))
                .retrieve().body(MembershipResultDto.class);
        assertTrue(leave.accepted(), "꺼져 있는 노드도 해지할 수 있다: " + leave.message());
        assertEquals(2L, write(2));
        int term = status(leader).term();

        start(D1);
        await("D1이 남은 멤버에게 듣고 멈춤", () -> status(D1).outReason() != null && status(D1).outReason().contains("해지"));
        assertEquals("NONE", status(D1).raftRole());
        sleep(6_000);
        assertEquals("LEADER", status(leader).raftRole(), "돌아온 해지 노드 때문에 리더가 바뀌지 않는다");
        assertEquals(term, status(leader).term(), "임기 번호도 그대로");
        assertEquals(3L, write(3));
    }

    /** 리더 층은 팀마다 노드 1대다: 팀원 노드와 이미 있는 팀의 둘째 노드는 리더 층에 들어오지 못한다 */
    @Test
    void theLeaderLayerTakesOneNodePerTeam() {
        int leader = leader();

        restartD1("--node.team=A");
        MembershipResultDto second = join(leader, new MemberJoinDto(null, null, url(D1), null));
        assertFalse(second.accepted());
        assertTrue(second.message().contains("팀 A") && second.message().contains("A1"), second.message());

        restartD1("--node.role=member", "--node.team=A", "--node.team-leader=A1");
        MembershipResultDto member = join(leader, new MemberJoinDto(null, null, url(D1), null));
        assertFalse(member.accepted());
        assertTrue(member.message().contains("팀원"), member.message());
        assertEquals(List.of("A1", "B1", "C1"), votingIds(status(leader)));
    }
}
