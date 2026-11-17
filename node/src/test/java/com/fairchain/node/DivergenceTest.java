package com.fairchain.node;

import com.fairchain.api.util.Sha256RecordHasher;
import com.fairchain.node.security.NodeKeys;
import com.fairchain.nodeapi.BlockRecordApi;
import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.NodeReportApi;
import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import com.fairchain.nodeapi.dto.DivergenceReportDto;
import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import com.fairchain.nodeapi.dto.ReasonCode;
import com.fairchain.nodeapi.dto.ReportAckDto;
import com.fairchain.nodeapi.dto.VerdictDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5단계: 불일치 판정 · 제외 · 복구 (미정사항 10 · 11 · 13 · 14번). 리더 층 3대(A1 · B1 · C1) + 팀 A의 팀원 A2.
 */
class DivergenceTest {

    private static final String SECRET = "divergence-test-secret";
    private static final String RECORD = "b".repeat(64);
    private static final String[] IDS = {"A1", "B1", "C1", "A2"};
    private static final int A2 = 3;

    @TempDir
    Path dir;

    private final String dbName = "div-" + UUID.randomUUID();
    private final int[] ports = new int[4];
    private final ConfigurableApplicationContext[] nodes = new ConfigurableApplicationContext[4];
    private final RestClient http;
    private final Sha256RecordHasher hasher = new Sha256RecordHasher();

    DivergenceTest() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(8000);
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
            stop(i);
        }
    }

    private void start(int i) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=" + ports[i],
                "--spring.datasource.url=" + jdbcUrl(i),
                "--node.id=" + IDS[i],
                "--node.members=A1=" + url(0) + ",B1=" + url(1) + ",C1=" + url(2),
                "--node.secret=" + SECRET,
                "--node.key-file=" + dir.resolve(IDS[i] + ".key"),
                "--node.raft.store-file=" + dir.resolve(IDS[i] + "-raft.sqlite"),
                "--spring.main.banner-mode=off",
                "--spring.jmx.enabled=false",
                "--logging.level.root=WARN"));
        if (i == A2) {
            args.add("--node.role=member");
            args.add("--node.url=" + url(i));
            args.add("--node.member-sync-ms=300");
        }
        args.addAll(extra.getOrDefault(i, List.of()));
        nodes[i] = new SpringApplicationBuilder(NodeApplication.class).run(args.toArray(String[]::new));
    }

    /** 노드별로 더할 설정 */
    private final Map<Integer, List<String>> extra = new java.util.HashMap<>();

    private void stop(int i) {
        if (nodes[i] != null) {
            nodes[i].close();
            nodes[i] = null;
        }
    }

    private String jdbcUrl(int i) {
        return "jdbc:h2:mem:" + dbName + "-" + i + ";DB_CLOSE_DELAY=-1";
    }

    private JdbcTemplate db(int i) {
        return new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(i), "sa", ""));
    }

    private String url(int i) {
        return "http://127.0.0.1:" + ports[i];
    }

    private static String key(int n) {
        return String.format("%064x", n);
    }

    private long write(int ticket) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (int i = 0; i < 3; i++) {
                if (nodes[i] == null) {
                    continue;
                }
                try {
                    BlockResultDto r = http.post().uri(url(i) + BlockRecordApi.RECORDS)
                            .body(new BlockRecordDto(key(ticket), 1L, RECORD)).retrieve().body(BlockResultDto.class);
                    if (r != null && r.accepted()) {
                        return r.blockIndex();
                    }
                } catch (Exception ignored) {
                    // 꺼지는 중
                }
            }
            sleep(300);
        }
        throw new AssertionError("기록되지 않음: ticket " + ticket);
    }

    private NodeLedgerStatusDto ledger(int i) {
        return http.get().uri(url(i) + NodeAuditApi.LEDGER_STATUS).retrieve().body(NodeLedgerStatusDto.class);
    }

    private NodeStatusDto status(int i) {
        return http.get().uri(url(i) + NodeAdminApi.STATUS).retrieve().body(NodeStatusDto.class);
    }

    private ReportAckDto report(int to, String suspect) {
        return http.post().uri(url(to) + NodeReportApi.REPORTS)
                .body(new DivergenceReportDto(suspect, null, null, "fairchain")).retrieve().body(ReportAckDto.class);
    }

    private int leader() {
        int[] found = {-1};
        await("리더", () -> {
            for (int i = 0; i < 3; i++) {
                if (nodes[i] != null && "LEADER".equals(status(i).raftRole())) {
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

    private void awaitKeys() {
        await("공개키 3개", () -> status(0).members().stream().allMatch(m -> m.publicKey() != null));
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 25_000;
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
        throw new AssertionError("25초 안에 충족되지 않음: " + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** 노드 i의 판정 기록 중 suspect에 대한 action들 (오래된 것부터) */
    private List<String> actionsAbout(int i, String suspect) {
        return status(i).verdicts().stream().filter(v -> suspect.equals(v.suspectId())).map(VerdictDto::action).toList();
    }

    /** 노드 i의 블록 from번부터 recordHash를 바꾸고 그 뒤 해시를 다시 계산해 맞춘다 (서명은 그대로) — "꼼꼼한 변조" */
    private void rewriteFrom(int i, long from) {
        JdbcTemplate db = db(i);
        long height = db.queryForObject("select max(block_index) from block", Long.class);
        String prev = db.queryForObject("select block_hash from block where block_index = ?", String.class, from - 1);
        for (long index = from; index <= height; index++) {
            Map<String, Object> row = db.queryForMap("select * from block where block_index = ?", index);
            String recordHash = index == from ? "e".repeat(64) : (String) row.get("RECORD_HASH");
            String hash = hasher.hash(String.valueOf(index), prev, (String) row.get("RECORD_KEY"),
                    String.valueOf(row.get("VERSION")), recordHash);
            db.update("update block set record_hash = ?, prev_block_hash = ?, block_hash = ? where block_index = ?",
                    recordHash, prev, hash, index);
            prev = hash;
        }
    }

    // ------------------------------------------------------------------ 시나리오

    /**
     * 해시까지 다시 맞춘 변조는 노드 스스로의 해시 검사로는 안 보인다. FairChain의 신고 → 리더가 해시 이진 탐색으로 #k를 찾고
     * #k 서명이 맞지 않아 제외 → 그 노드는 #k부터 다시 받아 복귀. 그동안 나머지 2대로 기록이 계속된다.
     */
    @Test
    void aRewrittenCopyIsFoundByBinarySearchExcludedAndRestoredFromK() {
        int leader = leader();
        awaitKeys();
        for (int t = 1; t <= 6; t++) {
            assertEquals((long) t, write(t));
        }
        await("3대 모두 6번", () -> sameHead(6, 0, 1, 2));
        int suspect = (leader + 1) % 3;
        rewriteFrom(suspect, 4);
        assertTrue(ledger(suspect).chainValid(), "해시를 다시 맞춘 변조는 해시 검사로는 정상으로 보인다");

        ReportAckDto ack = report((leader + 2) % 3, IDS[suspect]);   // 팔로워에게 보내도 리더에게 넘어간다
        assertTrue(ack.accepted());
        assertEquals(ReasonCode.DIVERGED, ack.reason());
        VerdictDto v = ack.verdict();
        assertNotNull(v);
        assertEquals(4L, v.blockIndex(), "처음 갈라진 블록 #k");
        assertEquals("EXCLUDED", v.action());
        assertTrue(v.differentFields().containsAll(List.of("recordHash", "blockHash", "signature")), v.differentFields().toString());
        assertEquals("e".repeat(64), v.found().recordHash());
        assertEquals(RECORD, v.expected().recordHash());

        assertEquals(7L, write(7), "판정 · 복구 중에도 나머지 2대로 기록");
        await("제외된 노드가 #4부터 다시 받아 복귀", () -> status(suspect).outReason() == null
                && "FOLLOWER".equals(status(suspect).raftRole()) && sameHead(7, 0, 1, 2));
        assertEquals(List.of("EXCLUDED", "RESTORED"), actionsAbout(leader, IDS[suspect]));
        assertTrue(status(leader).excluded().isEmpty());
        assertEquals(8L, write(8));
        await("3대 모두 8번", () -> sameHead(8, 0, 1, 2));
        // 다시 받은 블록도 그 노드 자신의 서명
        for (BlockDto b : http.get().uri(url(suspect) + NodeAuditApi.BLOCKS + "?from=1&limit=50").retrieve().body(BlockDto[].class)) {
            assertEquals(IDS[suspect], b.signerId());
        }
    }

    /** 해시가 깨진 노드는 스스로 빠지고 자신을 신고한다 → 앞에서부터 비교해 #k → 복구 */
    @Test
    void aBrokenNodeReportsItselfAndRecovers() {
        int leader = leader();
        awaitKeys();
        for (int t = 1; t <= 4; t++) {
            assertEquals((long) t, write(t));
        }
        await("3대 모두 4번", () -> sameHead(4, 0, 1, 2));
        int broken = (leader + 1) % 3;
        db(broken).update("update block set record_hash = ? where block_index = 3", "e".repeat(64));
        assertFalse(ledger(broken).chainValid());       // 조회가 체인 전체 검사 → 스스로 빠짐 → 자기 신고

        await("판정: CHAIN_INVALID #3 → 제외 → 복귀", () -> actionsAbout(leader, IDS[broken]).equals(List.of("EXCLUDED", "RESTORED")));
        VerdictDto v = status(leader).verdicts().stream()
                .filter(x -> IDS[broken].equals(x.suspectId()) && "EXCLUDED".equals(x.action())).findFirst().orElseThrow();
        assertEquals(ReasonCode.CHAIN_INVALID, v.reason());
        assertEquals(3L, v.blockIndex());
        await("복구된 노드가 같은 체인", () -> status(broken).outReason() == null && sameHead(4, 0, 1, 2));
    }

    /** 순회 검사(옵션, 미정사항 12번)를 켠 노드는 조회가 없어도 몇 초 안에 스스로 변조를 찾는다 → 신고 → 제외 → 복구 */
    @Test
    void thePatrolFindsATamperedBlockWithoutAnyQuery() {
        int leader = leader();
        int patrolled = (leader + 1) % 3;
        stop(patrolled);
        extra.put(patrolled, List.of("--node.patrol.enabled=true", "--node.patrol.blocks-per-sec=100"));
        start(patrolled);
        awaitKeys();
        for (int t = 1; t <= 4; t++) {
            assertEquals((long) t, write(t));
        }
        await("3대 모두 4번", () -> sameHead(4, 0, 1, 2));
        db(patrolled).update("update block set record_hash = ? where block_index = 2", "e".repeat(64));

        // 원장 상태 조회 없이 판정 기록만 지켜본다
        await("순회 검사 → 제외 #2 → 복귀", () -> actionsAbout(leader, IDS[patrolled]).equals(List.of("EXCLUDED", "RESTORED")));
        assertEquals(2L, status(leader).verdicts().get(0).blockIndex());
        await("같은 체인", () -> sameHead(4, 0, 1, 2));
    }

    @Test
    void reportsAboutHealthyNodesChangeNothing() {
        int leader = leader();
        awaitKeys();
        assertEquals(1L, write(1));
        await("3대 모두 1번", () -> sameHead(1, 0, 1, 2));
        assertEquals(ReasonCode.OK, report(leader, IDS[(leader + 1) % 3]).reason());
        assertEquals(ReasonCode.OK, report((leader + 1) % 3, IDS[leader]).reason(), "리더에 대한 신고: 리더가 자기 체인을 서명까지 검사");
        assertTrue(status(leader).excluded().isEmpty());
        assertTrue(status(leader).verdicts().isEmpty());
    }

    /** 1번 블록부터 다르면 표준대로 관리자가 해지 → 비우기 → learner로 재가입 (미정사항 11번) — 자동 복구하지 않는다 */
    @Test
    void aWholeChainDifferenceWaitsForTheAdmin() {
        int leader = leader();
        awaitKeys();
        assertEquals(1L, write(1));
        await("3대 모두 1번", () -> sameHead(1, 0, 1, 2));
        int broken = (leader + 1) % 3;
        db(broken).update("update block set record_hash = ? where block_index = 1", "e".repeat(64));
        assertFalse(ledger(broken).chainValid());
        await("관리자 확인 필요", () -> String.valueOf(status(broken).outReason()).contains("관리자"));
        assertEquals(List.of("EXCLUDED"), actionsAbout(leader, IDS[broken]));
        assertThrows(HttpServerErrorException.class, () -> http.post().uri(url(broken) + BlockRecordApi.VERIFY)
                .body(new BlockVerifyDto(key(1), 1L, RECORD)).retrieve().body(BlockVerifyResultDto.class),
                "빠진 노드는 검증에 답하지 않는다");
        assertEquals(2L, write(2));
    }

    /** 팀원 변조: 팀원 스스로 · 팀 리더가 신고 → 리더가 판정해 Raft 로그에 제외 → 팀원이 #k부터 다시 받아 복귀 */
    @Test
    void aTamperedMemberIsExcludedThroughTheLeaderLayerAndRestored() {
        leader();
        awaitKeys();
        for (int t = 1; t <= 3; t++) {
            assertEquals((long) t, write(t));
        }
        await("팀원이 3번", () -> sameHead(3, 0, A2));
        stop(A2);
        db(A2).update("update block set record_hash = ? where block_index = 2", "e".repeat(64));
        start(A2);

        await("팀원 제외 → 복귀", () -> actionsAbout(0, "A2").equals(List.of("EXCLUDED", "RESTORED")));
        VerdictDto v = status(0).verdicts().stream()
                .filter(x -> "A2".equals(x.suspectId()) && "EXCLUDED".equals(x.action())).findFirst().orElseThrow();
        assertEquals(2L, v.blockIndex());
        assertEquals(4L, write(4));
        await("복귀한 팀원이 4번까지", () -> status(A2).outReason() == null && sameHead(4, 0, 1, 2, A2));
    }

    /**
     * #k의 두 버전이 모두 서명이 맞는데 내용이 다르다 = 키를 가진 쪽이 확정되지 않은 블록에 서명했다 (키 유출 의심).
     * 자동으로 판정하지 않고 기록을 멈춘다 → 관리자가 확인 뒤 재개 (11/06 변경안 6장).
     */
    @Test
    void twoValidSignaturesOnDifferentBlocksHaltWritingUntilTheAdminResumes() {
        int leader = leader();
        awaitKeys();
        for (int t = 1; t <= 3; t++) {
            assertEquals((long) t, write(t));
        }
        await("팀원이 3번", () -> sameHead(3, 0, A2));
        stop(A2);
        // 훔친 A1 키로 팀원 사본의 3번 블록을 다른 내용으로 바꿔 서명까지 맞춘다
        NodeKeys stolen = new NodeKeys("A1", dir.resolve("A1.key"));
        JdbcTemplate db = db(A2);
        String prev = db.queryForObject("select prev_block_hash from block where block_index = 3", String.class);
        String forgedHash = hasher.hash("3", prev, key(3), "1", "f".repeat(64));
        BlockProposalDto forged = stolen.sign(new BlockProposalDto(3L, prev, key(3), 1L, "f".repeat(64), 0L, forgedHash));
        db.update("update block set record_hash = ?, block_hash = ?, signer_id = ?, signature = ? where block_index = 3",
                "f".repeat(64), forgedHash, "A1", forged.signature());
        start(A2);

        await("기록 중단", () -> status(leader).halted());
        assertEquals(List.of("HALTED"), actionsAbout(leader, "A2").subList(0, 1));
        BlockResultDto refused = http.post().uri(url(leader) + BlockRecordApi.RECORDS)
                .body(new BlockRecordDto(key(9), 1L, RECORD)).retrieve().body(BlockResultDto.class);
        assertFalse(refused.accepted());
        assertEquals(ReasonCode.DIVERGED, refused.reason());

        stop(A2);       // 관리자가 원인(유출된 키를 쓴 사본)을 치운 뒤 재개
        MembershipResultDto resumed = http.post().uri(url((leader + 1) % 3) + NodeAdminApi.RESUME)
                .retrieve().body(MembershipResultDto.class);
        assertTrue(resumed.accepted(), resumed.message());
        await("재개", () -> !status(leader).halted());
        assertEquals(4L, write(4));
        assertNull(status(leader).outReason());
    }
}
