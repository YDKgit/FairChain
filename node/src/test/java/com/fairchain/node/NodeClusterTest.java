package com.fairchain.node;

import com.fairchain.api.util.Sha256RecordHasher;
import com.fairchain.node.security.NodeKeys;
import com.fairchain.nodeapi.BlockRecordApi;
import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.nodeapi.NodeConsensusApi;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.NodeVerificationApi;
import com.fairchain.nodeapi.dto.BlockBatchVerifyRequestDto;
import com.fairchain.nodeapi.dto.BlockBatchVerifyResponseDto;
import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.MemberInfoDto;
import com.fairchain.nodeapi.dto.MemberJoinDto;
import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyItemDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 리더 층 노드 3대(각자 포트 · 각자 H2 · 각자 Raft 상태 파일)를 띄워 HTTP로만 확인하는 통합 테스트.
 * FairChain은 지금처럼 아무 노드에나 기록을 보낸다.
 */
class NodeClusterTest {

    private static final String SECRET = "cluster-test-secret";
    private static final String RECORD = "b".repeat(64);
    private static final String[] IDS = {"A1", "B1", "C1"};

    @TempDir
    Path dir;

    private final String dbName = "cluster-" + UUID.randomUUID();
    private final int[] ports = new int[3];
    private final ConfigurableApplicationContext[] nodes = new ConfigurableApplicationContext[3];
    private final RestClient http;

    NodeClusterTest() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(4000);
        http = RestClient.builder().requestFactory(factory).defaultHeader(NodeHeaders.NODE_SECRET, SECRET).build();
    }

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
        String members = "A1=" + url(0) + ",B1=" + url(1) + ",C1=" + url(2);
        List<String> args = new ArrayList<>(List.of(
                "--server.port=" + ports[i],
                // 메모리 DB를 노드마다 따로 두고, 노드를 내렸다 올려도 내용이 남게 한다
                "--spring.datasource.url=" + jdbcUrl(i),
                "--node.id=" + IDS[i],
                "--node.members=" + members,
                "--node.secret=" + SECRET,
                "--node.key-file=" + dir.resolve(IDS[i] + ".key"),
                "--node.raft.store-file=" + dir.resolve(IDS[i] + "-raft.sqlite"),
                "--spring.main.banner-mode=off",
                "--spring.jmx.enabled=false",
                "--logging.level.root=WARN"));
        args.addAll(extraArgs);
        nodes[i] = new SpringApplicationBuilder(NodeApplication.class).run(args.toArray(String[]::new));
    }

    /** 모든 노드에 더할 설정 (테스트 안에서 클러스터를 다시 띄울 때) */
    private final List<String> extraArgs = new ArrayList<>();

    private String jdbcUrl(int i) {
        return "jdbc:h2:mem:" + dbName + "-" + i + ";DB_CLOSE_DELAY=-1";
    }

    /** 꺼진 노드의 H2를 직접 고친다 (메모리 DB는 프로세스 안에 남아 있다) */
    private JdbcTemplate offlineDb(int i) {
        return new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(i), "sa", ""));
    }

    private void restartClusterWith(String... args) {
        for (int i = 0; i < 3; i++) {
            stop(i);
        }
        extraArgs.clear();
        extraArgs.addAll(List.of(args));
        for (int i = 0; i < 3; i++) {
            start(i);
        }
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

    /** FairChain처럼: 켜져 있는 노드에 차례로 보내고, 실패하면 0.5초 뒤 처음부터 다시 */
    private long submitUntilAccepted(int ticket, long version, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (int i = 0; i < 3; i++) {
                if (nodes[i] == null) {
                    continue;
                }
                try {
                    BlockResultDto r = submit(i, ticket, version);
                    if (r != null && r.accepted()) {
                        return r.blockIndex();
                    }
                } catch (Exception ignored) {
                    // 꺼지는 중인 노드
                }
            }
            sleep(500);
        }
        throw new AssertionError(timeoutMillis + "ms 안에 기록되지 않음: ticket " + ticket);
    }

    private boolean verified(int node, int ticket, long version) {
        return http.post().uri(url(node) + BlockRecordApi.VERIFY)
                .body(new BlockVerifyDto(key(ticket), version, RECORD))
                .retrieve().body(BlockVerifyResultDto.class).valid();
    }

    private NodeLedgerStatusDto ledger(int node) {
        return http.get().uri(url(node) + NodeAuditApi.LEDGER_STATUS).retrieve().body(NodeLedgerStatusDto.class);
    }

    private NodeStatusDto status(int node) {
        return http.get().uri(url(node) + NodeAdminApi.STATUS).retrieve().body(NodeStatusDto.class);
    }

    /** 켜져 있는 노드가 모두 같은 리더를 가리킬 때 그 리더 번호 */
    private int awaitLeader() {
        int[] found = {-1};
        await("리더 선출", () -> {
            String leader = null;
            for (int i = 0; i < 3; i++) {
                if (nodes[i] == null) {
                    continue;
                }
                NodeStatusDto s = status(i);
                if (s.leaderId() == null || (leader != null && !leader.equals(s.leaderId()))) {
                    return false;
                }
                leader = s.leaderId();
            }
            for (int i = 0; i < 3; i++) {
                if (IDS[i].equals(leader) && nodes[i] != null && "LEADER".equals(status(i).raftRole())) {
                    found[0] = i;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    private boolean sameHead(long height, int... which) {
        String hash = null;
        for (int i : which) {
            NodeLedgerStatusDto s = ledger(i);
            if (s.height() != height || !s.chainValid() || (hash != null && !hash.equals(s.headHash()))) {
                return false;
            }
            hash = s.headHash();
        }
        return true;
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (Exception ignored) {
                // 아직 켜지는 중
            }
            sleep(100);
        }
        throw new AssertionError("15초 안에 충족되지 않음: " + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ 시나리오

    @Test
    void anyNodeAcceptsWritesAndAllNodesKeepTheSameChain() {
        int leader = awaitLeader();
        int follower = (leader + 1) % 3;

        assertEquals(1L, submit(leader, 1, 1).blockIndex());
        BlockResultDto viaFollower = submit(follower, 2, 1);
        assertTrue(viaFollower.accepted(), "팔로워는 리더에게 넘긴다");
        assertEquals(2L, viaFollower.blockIndex());
        assertEquals(3L, submit((leader + 2) % 3, 3, 1).blockIndex());

        // 같은 기록을 다시 보내면 (어느 노드든) 새 블록 없이 같은 번호
        assertEquals(1L, submit(follower, 1, 1).blockIndex());
        // 버전이 오르지 않는 기록은 거부
        assertFalse(submit(leader, 2, 1).accepted() && submit(leader, 2, 1).blockIndex() != 2L);
        BlockResultDto stale = http.post().uri(url(leader) + BlockRecordApi.RECORDS)
                .body(new BlockRecordDto(key(3), 1L, "c".repeat(64))).retrieve().body(BlockResultDto.class);
        assertFalse(stale.accepted());

        await("3대 모두 3번 블록, 같은 해시", () -> sameHead(3, 0, 1, 2));
        for (int i = 0; i < 3; i++) {
            assertTrue(verified(i, 3, 1));
        }
    }

    @Test
    void writesContinueWithOneNodeDownAndTheNodeCatchesUpAfterRestart() {
        int leader = awaitLeader();
        int follower = (leader + 1) % 3;
        assertEquals(1L, submit(leader, 1, 1).blockIndex());

        stop(follower);
        assertEquals(2L, submit(leader, 2, 1).blockIndex());
        assertEquals(3L, submit(leader, 3, 1).blockIndex());
        assertEquals(4L, submit((leader + 2) % 3, 4, 1).blockIndex(), "남은 팔로워로 보내도 리더에게 넘어가 기록된다");

        start(follower);
        await("다시 켠 노드가 4번 블록까지 따라잡음", () -> sameHead(4, 0, 1, 2));
        assertTrue(verified(follower, 4, 1));
        assertEquals(5L, submit(follower, 5, 1).blockIndex(), "따라잡은 노드로 보내도 기록된다");
        await("3대 모두 5번 블록", () -> sameHead(5, 0, 1, 2));
    }

    @Test
    void aNewLeaderIsElectedWhenTheLeaderStopsAndWritesGoOn() {
        int leader = awaitLeader();
        assertEquals(1L, submit(leader, 1, 1).blockIndex());
        int termBefore = status(leader).term();

        stop(leader);
        long stoppedAt = System.currentTimeMillis();
        int next = awaitLeader();
        long electedAfter = System.currentTimeMillis() - stoppedAt;
        System.out.println("[측정] 리더를 끈 뒤 새 리더까지 " + electedAfter + " ms (하트비트 1초 · 시간 제한 4초)");

        assertNotEquals(leader, next);
        assertTrue(status(next).term() > termBefore, "새 임기");
        assertTrue(electedAfter < 7_000, "새 리더까지 " + electedAfter + " ms");
        assertEquals(2L, submitUntilAccepted(2, 1, 5_000));

        start(leader);
        await("예전 리더도 팔로워로 따라잡음", () -> sameHead(2, 0, 1, 2));
        assertEquals(next, awaitLeader(), "돌아온 노드가 리더를 빼앗지 않는다");
    }

    @Test
    void withTwoNodesDownNothingIsConfirmedAndTheSameRecordGoesThroughLater() {
        int leader = awaitLeader();
        assertEquals(1L, submit(leader, 1, 1).blockIndex());
        int a = (leader + 1) % 3;
        int b = (leader + 2) % 3;

        stop(a);
        stop(b);
        BlockResultDto alone = submit(leader, 2, 1);
        assertFalse(alone.accepted(), "과반(2대)이 없으면 확정하지 않는다");
        assertEquals(1L, ledger(leader).height());

        start(a);
        assertEquals(2L, submitUntilAccepted(2, 1, 15_000), "같은 기록을 다시 보내면 블록 하나만 생긴다");
        assertEquals(3L, submitUntilAccepted(3, 1, 5_000));
        start(b);
        await("3대 모두 3번 블록", () -> sameHead(3, 0, 1, 2));
    }

    @Test
    void theWholeClusterRestartsWithItsTermAndChain() {
        int leader = awaitLeader();
        for (int t = 1; t <= 3; t++) {
            assertEquals((long) t, submit(leader, t, 1).blockIndex());
        }
        await("3대 모두 3번 블록", () -> sameHead(3, 0, 1, 2));
        int term = status(leader).term();

        for (int i = 0; i < 3; i++) {
            stop(i);
        }
        for (int i = 0; i < 3; i++) {
            start(i);
        }
        int again = awaitLeader();
        assertTrue(status(again).term() > term, "임기는 디스크에서 이어진다");
        assertEquals(4L, submitUntilAccepted(4, 1, 5_000));
        assertEquals(1L, submit(again, 1, 1).blockIndex(), "재시작 전 기록도 중복으로 알아본다");
        await("3대 모두 4번 블록", () -> sameHead(4, 0, 1, 2));
    }

    @Test
    void aTamperedNodeLeavesTheLeaderLayerWhileTheOtherTwoKeepWorking() {
        int leader = awaitLeader();
        assertEquals(1L, submit(leader, 1, 1).blockIndex());
        await("3대 모두 1번 블록", () -> sameHead(1, 0, 1, 2));
        int tampered = (leader + 1) % 3;

        new JdbcTemplate(nodes[tampered].getBean(DataSource.class))
                .update("update block set record_hash = ? where block_index = 1", "e".repeat(64));

        assertFalse(ledger(tampered).chainValid());
        assertThrows(org.springframework.web.client.HttpServerErrorException.class, () -> verified(tampered, 1, 1),
                "빠진 노드는 검증에 답하지 않는다 (503)");
        assertEquals("NONE", status(tampered).raftRole(), "체인이 깨진 노드는 Raft에서 빠진다");

        assertEquals(2L, submitUntilAccepted(2, 1, 5_000), "나머지 2대로 과반");
        int healthy = (leader + 2) % 3;
        await("정상 노드 2대가 2번 블록", () -> sameHead(2, leader, healthy));
        assertEquals(1L, ledger(tampered).height(), "빠진 노드는 새 블록을 받지 않는다");
        assertFalse(submit(tampered, 3, 1).accepted(), "빠진 노드는 기록을 받지 않는다");
    }

    @Test
    void requestWithoutTheNodeSecretIsRejected() {
        HttpClientErrorException error = assertThrows(HttpClientErrorException.class,
                () -> RestClient.create().get().uri(url(0) + NodeAuditApi.LEDGER_STATUS).retrieve().toBodilessEntity());
        assertEquals(401, error.getStatusCode().value());
    }

    // ------------------------------------------------------------------ 3단계: 키와 서명

    /** 켜져 있는 노드 모두에서 공개키 3개가 멤버 명단(Raft 로그)에 등록될 때까지 */
    private Map<String, String> awaitRegisteredKeys() {
        Map<String, String> keys = new HashMap<>();
        await("공개키 3개 등록", () -> {
            keys.clear();
            for (int i = 0; i < 3; i++) {
                if (nodes[i] == null) {
                    continue;
                }
                NodeStatusDto s = status(i);
                for (MemberInfoDto m : s.members()) {
                    if (m.publicKey() == null) {
                        return false;
                    }
                    if (keys.containsKey(m.id()) && !keys.get(m.id()).equals(m.publicKey())) {
                        return false;
                    }
                    keys.put(m.id(), m.publicKey());
                }
                if (!s.publicKey().equals(keys.get(IDS[i]))) {
                    return false;
                }
            }
            return keys.size() == 3;
        });
        return Map.copyOf(keys);
    }

    private List<BlockDto> storedBlocks(int node) {
        BlockDto[] blocks = http.get().uri(url(node) + NodeAuditApi.BLOCKS + "?from=1&limit=200").retrieve().body(BlockDto[].class);
        return blocks == null ? List.of() : List.of(blocks);
    }

    /** 그 노드의 블록이 모두 그 노드 자신의 서명을 달고 있고, 등록된 공개키로 확인되는지 */
    private void assertSignedByItself(int node, Map<String, String> keys, long height) {
        List<BlockDto> blocks = storedBlocks(node);
        assertEquals(height, blocks.size());
        for (BlockDto b : blocks) {
            assertEquals(IDS[node], b.signerId(), "블록 #" + b.blockIndex() + " 서명자");
            BlockProposalDto signed = new BlockProposalDto(b.blockIndex(), b.prevBlockHash(), b.recordKey(), b.version(),
                    b.recordHash(), 0L, b.blockHash(), b.signerId(), b.signature());
            assertTrue(NodeKeys.verify(keys.get(IDS[node]), signed), IDS[node] + " 블록 #" + b.blockIndex() + " 서명 확인");
        }
    }

    @Test
    void everyBlockOnEveryLeaderLayerNodeCarriesItsOwnVerifiableSignature() {
        int leader = awaitLeader();
        Map<String, String> keys = awaitRegisteredKeys();
        for (int t = 1; t <= 4; t++) {
            assertEquals((long) t, submit((leader + t) % 3, t, 1).blockIndex());
        }
        await("3대 모두 4번 블록", () -> sameHead(4, 0, 1, 2));
        for (int i = 0; i < 3; i++) {
            assertSignedByItself(i, keys, 4);
        }
        // 같은 블록이라도 사본마다 서명이 다르다 (Fabric: 쓴 노드가 서명)
        assertNotEquals(storedBlocks(0).get(0).signature(), storedBlocks(1).get(0).signature());
    }

    @Test
    void aRegisteredKeyCannotBeReplaced() {
        awaitLeader();
        Map<String, String> keys = awaitRegisteredKeys();
        String otherKey = new NodeKeys("B1", dir.resolve("stolen.key")).publicKey();
        MembershipResultDto r = http.post().uri(url(0) + NodeConsensusApi.KEYS)
                .body(new MemberJoinDto("B1", "B", url(1), otherKey)).retrieve().body(MembershipResultDto.class);
        assertFalse(r.accepted(), r.message());
        MembershipResultDto stranger = http.post().uri(url(0) + NodeConsensusApi.KEYS)
                .body(new MemberJoinDto("Z9", "Z", "http://127.0.0.1:1", otherKey)).retrieve().body(MembershipResultDto.class);
        assertFalse(stranger.accepted(), "Raft 멤버가 아닌 노드는 키 등록으로 들어올 수 없다");
        assertEquals(keys, awaitRegisteredKeys());
    }

    /**
     * 기록을 바꾸고 그 뒤 해시를 처음부터 다시 계산해 맞춰 놓은 장부 — 해시 검사로는 정상으로 보인다.
     * 개인키 없이는 서명을 다시 만들 수 없어 켤 때 서명 검사에서 잡힌다 (미정사항 9번의 이유).
     */
    @Test
    void aLedgerRewrittenWithFreshHashesIsCaughtByItsSignaturesAtStartup() {
        int leader = awaitLeader();
        awaitRegisteredKeys();
        for (int t = 1; t <= 3; t++) {
            assertEquals((long) t, submit(leader, t, 1).blockIndex());
        }
        await("3대 모두 3번 블록", () -> sameHead(3, 0, 1, 2));
        int tampered = (leader + 1) % 3;
        stop(tampered);

        JdbcTemplate db = offlineDb(tampered);
        Sha256RecordHasher hasher = new Sha256RecordHasher();
        String prev = "0".repeat(64);
        for (long index = 1; index <= 3; index++) {
            Map<String, Object> row = db.queryForMap("select * from block where block_index = ?", index);
            String recordHash = index == 1 ? "e".repeat(64) : (String) row.get("RECORD_HASH");
            String hash = hasher.hash(String.valueOf(index), prev, (String) row.get("RECORD_KEY"),
                    String.valueOf(row.get("VERSION")), recordHash);
            db.update("update block set record_hash = ?, prev_block_hash = ?, block_hash = ? where block_index = ?",
                    recordHash, prev, hash, index);
            prev = hash;
        }

        start(tampered);
        assertEquals("NONE", status(tampered).raftRole(), "서명이 맞지 않는 장부로는 Raft에 들어오지 않는다");
        assertFalse(ledger(tampered).chainValid());
        assertEquals(4L, submitUntilAccepted(4, 1, 8_000), "나머지 2대로 과반");
        int healthy = (leader + 2) % 3;
        await("정상 노드 2대가 4번 블록", () -> sameHead(4, leader, healthy));
        assertEquals(3L, ledger(tampered).height());
    }

    @Test
    void aBlockWithoutItsSignatureCountsAsTampered() {
        int leader = awaitLeader();
        assertEquals(1L, submit(leader, 1, 1).blockIndex());
        await("3대 모두 1번 블록", () -> sameHead(1, 0, 1, 2));
        int tampered = (leader + 1) % 3;
        stop(tampered);
        offlineDb(tampered).update("update block set signature = null, signer_id = null where block_index = 1");

        start(tampered);
        assertEquals("NONE", status(tampered).raftRole());
        assertFalse(ledger(tampered).chainValid());
    }

    /**
     * 스냅샷으로 로그 앞부분이 잘린 뒤 돌아온 노드: Raft 로그 대신 스냅샷을 받고, 빠진 블록은 다른 리더 층 노드에서
     * {@code GET /node/consensus/blocks}로 받는다. 보낸 노드의 서명을 확인하고 자기 키로 다시 서명해 쓴다.
     */
    @Test
    void aNodeThatMissedASnapshotPullsSignedBlocksFromThePeers() {
        restartClusterWith("--node.raft.snapshot-every=5", "--logging.level.com.fairchain.node.raft.RaftNodeHolder=INFO");
        int leader = awaitLeader();
        Map<String, String> keys = awaitRegisteredKeys();
        assertEquals(1L, submit(leader, 1, 1).blockIndex());
        int late = (leader + 1) % 3;
        await("3대 모두 1번 블록", () -> sameHead(1, 0, 1, 2));
        stop(late);

        for (int t = 2; t <= 21; t++) {
            assertEquals((long) t, submit(leader, t, 1).blockIndex());
        }
        start(late);
        await("늦게 온 노드가 21번 블록까지", () -> sameHead(21, 0, 1, 2));
        assertSignedByItself(late, keys, 21);
        assertTrue(verified(late, 21, 1));
        assertEquals(22L, submitUntilAccepted(22, 1, 5_000));
        await("3대 모두 22번 블록", () -> sameHead(22, 0, 1, 2));
    }

    // ------------------------------------------------------------------ 조회가 몰릴 때 (2026-11-25 부하 테스트에서 A1이 30초씩 멈춤)

    /**
     * 원장 상태 조회(체인 전체 검사)가 한꺼번에 몰리는 동안에도 리더는 블록을 계속 붙인다.
     * 전에는 요청이 응답을 다 보낼 때까지 DB 연결을 쥐고 있었다 (open-in-view). 연결 10개를 쥔 요청들이 장부 잠금을 기다리고,
     * 블록을 붙이는 스레드는 장부 잠금을 쥔 채 DB 연결을 기다려 서로 멈췄다 — DB 연결 대기 시간(기본 30초)이 지나야 풀렸다.
     * 같은 테스트가 H2의 결과 재사용(OPTIMIZE_REUSE_RESULTS)도 잡는다: 조회와 커밋이 겹치면 옛 "마지막 블록"이 연결에 남아
     * 리더가 자기 블록을 건너뛰고 다음 기록을 BEHIND로 거절했다 (7번에 3번꼴).
     */
    @Test
    void manyLedgerChecksAtOnceDoNotStopTheLeaderFromWriting() {
        // 다시 멈추게 되면 30초가 아니라 3초 만에 드러나게
        restartClusterWith("--spring.datasource.hikari.connection-timeout=3000");
        int leader = awaitLeader();
        for (int t = 1; t <= 20; t++) {
            assertEquals((long) t, submit(leader, t, 1).blockIndex());
        }

        ExecutorService readers = Executors.newFixedThreadPool(24);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger checks = new AtomicInteger();
        long slowest = 0;
        try {
            for (int i = 0; i < 24; i++) {
                readers.submit(() -> {
                    while (!stop.get()) {
                        try {
                            if (ledger(leader).chainValid()) {
                                checks.incrementAndGet();
                            }
                        } catch (Exception ignored) {
                            // 멈춘 노드는 답하지 않는다 — 아래 기록 확인에서 드러난다
                        }
                    }
                });
            }
            for (int t = 21; t <= 80; t++) {
                long begin = System.currentTimeMillis();
                BlockResultDto r = submit(leader, t, 1);
                slowest = Math.max(slowest, System.currentTimeMillis() - begin);
                assertTrue(r.accepted(), "블록 #" + t + ": 조회가 몰리는 동안에도 기록된다 (" + r.reason() + ")");
            }
        } finally {
            stop.set(true);
            readers.shutdownNow();
        }
        System.out.println("[측정] 원장 상태 조회 24개가 계속 도는 동안 블록 60개 기록 — 가장 느린 기록 " + slowest
                + " ms · 조회 " + checks.get() + "번");
        assertTrue(checks.get() > 0, "조회도 답을 받았다");
        assertEquals("LEADER", status(leader).raftRole(), "리더가 멈추지 않았으므로 리더가 바뀌지 않는다");
        await("3대 모두 80번 블록", () -> sameHead(80, 0, 1, 2));
    }

    private BlockBatchVerifyResponseDto verifyBatch(int node, BlockVerifyItemDto... items) {
        return http.post().uri(url(node) + NodeVerificationApi.VERIFY_BATCH)
                .body(new BlockBatchVerifyRequestDto(List.of(items))).retrieve().body(BlockBatchVerifyResponseDto.class);
    }

    /**
     * 검증 조회는 답하는 블록 하나만 본다 (블록 해시 재계산 · 앞 블록 · 뒤 블록과의 연결) — 조회마다 체인 전체를 읽지 않는다.
     * 그 블록이 고쳐져 있으면 고친 값으로 물어도 통과하지 않고, 그때 체인 전체를 검사해 손상을 확정한다.
     * 다른 블록의 조작은 원장 상태 조회 · 켤 때 검사 · 순회 검사가 잡는다.
     */
    @Test
    void aCheckLooksOnlyAtTheBlockItAnswersWith() {
        int leader = awaitLeader();
        for (int t = 1; t <= 5; t++) {
            assertEquals((long) t, submit(leader, t, 1).blockIndex());
        }
        await("3대 모두 5번 블록", () -> sameHead(5, 0, 1, 2));
        String forged = "e".repeat(64);

        // 기록 키로 최신 블록을 찾는 인덱스 — 장부가 길어져도 검증 · 블록 붙이기가 장부 전체를 훑지 않는다
        JdbcTemplate db = new JdbcTemplate(nodes[leader].getBean(DataSource.class));
        assertTrue(db.queryForObject("select count(*) from information_schema.index_columns "
                + "where table_name = 'BLOCK' and column_name = 'RECORD_KEY'", Integer.class) > 0);

        // 노드 1: 2번 블록의 지문만 고친다 (블록 해시는 그대로) — 돌고 있는 노드의 H2
        int first = (leader + 1) % 3;
        new JdbcTemplate(nodes[first].getBean(DataSource.class))
                .update("update block set record_hash = ? where block_index = 2", forged);
        BlockBatchVerifyResponseDto answer = verifyBatch(first,
                new BlockVerifyItemDto(4L, key(4), 1L, RECORD), new BlockVerifyItemDto(2L, key(2), 1L, forged));
        assertTrue(answer.results().get(0).matched() && answer.results().get(0).confirmed(),
                "4번 블록을 묻는 조회는 4번 블록만 본다");
        assertTrue(answer.results().get(1).matched(), "고친 값으로 물었으므로 저장된 값과는 같다");
        assertFalse(answer.results().get(1).confirmed(), "블록 해시가 내용과 맞지 않아 통과하지 않는다");
        assertFalse(verified(first, 4, 1), "손상을 확인한 뒤에는 어떤 조회도 통과시키지 않는다");
        assertFalse(ledger(first).chainValid());
        assertEquals("NONE", status(first).raftRole(), "원장 상태 조회에서 체인 손상으로 빠진다");

        // 노드 2: 지문을 고치고 그 블록의 해시까지 다시 계산해 맞춘다 — 뒤 블록이 가리키는 해시와 어긋난다
        int second = (leader + 2) % 3;
        JdbcTemplate other = new JdbcTemplate(nodes[second].getBean(DataSource.class));
        String previous = other.queryForObject("select prev_block_hash from block where block_index = 3", String.class);
        String rehashed = new Sha256RecordHasher().hash("3", previous, key(3), "1", forged);
        other.update("update block set record_hash = ?, block_hash = ? where block_index = 3", forged, rehashed);
        assertTrue(verified(second, 5, 1), "5번 블록을 묻는 조회는 5번 블록만 본다");
        assertFalse(http.post().uri(url(second) + BlockRecordApi.VERIFY).body(new BlockVerifyDto(key(3), 1L, forged))
                .retrieve().body(BlockVerifyResultDto.class).valid(), "뒤 블록과 이어지지 않아 통과하지 않는다");
        assertFalse(ledger(second).chainValid());
    }

    @Test
    void statusShowsTheLeaderLayer() {
        int leader = awaitLeader();
        NodeStatusDto s = status(leader);
        assertEquals(IDS[leader], s.id());
        assertEquals(IDS[leader].substring(0, 1), s.team());
        assertEquals("leader-layer", s.role());
        assertNotNull(s.term());
        List<String> ids = new ArrayList<>();
        s.members().forEach(m -> ids.add(m.id()));
        assertEquals(List.of("A1", "B1", "C1"), ids);
        assertTrue(s.learners().isEmpty());
        assertTrue(s.excluded().isEmpty());
    }
}
