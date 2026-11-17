package com.fairchain.node;

import com.fairchain.node.security.NodeKeys;
import com.fairchain.nodeapi.BlockRecordApi;
import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.NodeMemberApi;
import com.fairchain.nodeapi.NodeTeamApi;
import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import com.fairchain.nodeapi.dto.MemberAckDto;
import com.fairchain.nodeapi.dto.MemberInfoDto;
import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import com.fairchain.nodeapi.dto.ReasonCode;
import com.fairchain.nodeapi.dto.TeamHelloDto;
import com.fairchain.nodeapi.dto.TeamMemberDto;
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

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 팀원 = 투표하지 않는 사본 (11/12 답변 2번). 리더 층 3대(A1 · B1 · C1) + 팀 A의 팀원 2대(A2 · A3)를 실제로 띄운다.
 */
class TeamMemberTest {

    private static final String SECRET = "team-test-secret";
    private static final String RECORD = "b".repeat(64);
    /** 0~2: 리더 층 (팀 리더), 3~4: 팀 A의 팀원 */
    private static final String[] IDS = {"A1", "B1", "C1", "A2", "A3"};
    private static final int A1 = 0;
    private static final int A2 = 3;
    private static final int A3 = 4;

    @TempDir
    Path dir;

    private final String dbName = "team-" + UUID.randomUUID();
    private final int[] ports = new int[5];
    private final ConfigurableApplicationContext[] nodes = new ConfigurableApplicationContext[5];
    private final RestClient http;

    TeamMemberTest() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(4000);
        http = RestClient.builder().requestFactory(factory).defaultHeader(NodeHeaders.NODE_SECRET, SECRET).build();
    }

    @BeforeEach
    void startNodes() throws IOException {
        for (int i = 0; i < 5; i++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                ports[i] = socket.getLocalPort();
            }
        }
        for (int i = 0; i < 5; i++) {
            start(i);
        }
    }

    @AfterEach
    void stopNodes() {
        for (int i = 0; i < 5; i++) {
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
        if (i >= 3) {
            args.add("--node.role=member");
            args.add("--node.url=" + url(i));
            args.add("--node.member-sync-ms=300");
        }
        nodes[i] = new SpringApplicationBuilder(NodeApplication.class).run(args.toArray(String[]::new));
    }

    private void stop(int i) {
        if (nodes[i] != null) {
            nodes[i].close();
            nodes[i] = null;
        }
    }

    private String jdbcUrl(int i) {
        return "jdbc:h2:mem:" + dbName + "-" + i + ";DB_CLOSE_DELAY=-1";
    }

    private String url(int i) {
        return "http://127.0.0.1:" + ports[i];
    }

    private static String key(int n) {
        return String.format("%064x", n);
    }

    private long submitUntilAccepted(int ticket, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
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

    private List<BlockDto> storedBlocks(int i) {
        BlockDto[] blocks = http.get().uri(url(i) + NodeAuditApi.BLOCKS + "?from=1&limit=200").retrieve().body(BlockDto[].class);
        return blocks == null ? List.of() : List.of(blocks);
    }

    private Optional<TeamMemberDto> teamMember(String id) {
        return status(A1).teamMembers().stream().filter(m -> m.id().equals(id)).findFirst();
    }

    private String publicKeyOf(String id) {
        for (int i = 0; i < 3; i++) {
            if (nodes[i] != null) {
                for (MemberInfoDto m : status(i).members()) {
                    if (m.id().equals(id) && m.publicKey() != null) {
                        return m.publicKey();
                    }
                }
            }
        }
        return null;
    }

    /** 노드들의 마지막 블록 번호 · 해시가 같은지 */
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
        long deadline = System.currentTimeMillis() + 20_000;
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

    private void awaitKeys() {
        await("공개키 3개 등록", () -> publicKeyOf("A1") != null && publicKeyOf("B1") != null && publicKeyOf("C1") != null);
    }

    // ------------------------------------------------------------------ 시나리오

    @Test
    void membersStoreTheBlocksSignedByTheirTeamLeader() {
        awaitKeys();
        for (int t = 1; t <= 3; t++) {
            assertEquals((long) t, submitUntilAccepted(t, 8_000));
        }
        await("팀원 2대가 리더 층과 같은 3번 블록", () -> sameHead(3, 0, 1, 2, A2, A3));

        String teamLeaderKey = publicKeyOf("A1");
        for (int member : new int[]{A2, A3}) {
            for (BlockDto b : storedBlocks(member)) {
                assertEquals("A1", b.signerId(), IDS[member] + " 블록 #" + b.blockIndex() + "는 팀 리더의 사본");
                assertTrue(NodeKeys.verify(teamLeaderKey, new BlockProposalDto(b.blockIndex(), b.prevBlockHash(),
                        b.recordKey(), b.version(), b.recordHash(), 0L, b.blockHash(), b.signerId(), b.signature())));
            }
            NodeStatusDto s = status(member);
            assertEquals("member", s.role());
            assertEquals("NONE", s.raftRole(), "팀원은 Raft를 돌리지 않는다");
            assertEquals("A1", s.teamLeader());
            assertTrue(http.post().uri(url(member) + BlockRecordApi.VERIFY).body(new BlockVerifyDto(key(3), 1L, RECORD))
                    .retrieve().body(BlockVerifyResultDto.class).valid());
        }

        await("팀 리더가 팀원 2대를 저장함(OK) · 3번으로 봄", () -> {
            List<TeamMemberDto> team = status(A1).teamMembers();
            return team.size() == 2 && team.stream().allMatch(m -> m.state() == ReasonCode.OK && m.lastBlockIndex() == 3);
        });
        assertTrue(status(1).teamMembers().isEmpty(), "다른 팀 리더는 팀 A의 팀원을 갖지 않는다");
        // 팀원은 Raft 멤버가 아니다 — 리더 층 명단은 그대로 3대
        assertEquals(3, status(1).members().size());
    }

    @Test
    void whenTheTeamLeaderIsDownMembersTakeBlocksFromAnotherTeamLeader() {
        awaitKeys();
        assertEquals(1L, submitUntilAccepted(1, 8_000));
        await("팀원이 1번 블록", () -> sameHead(1, 0, A2, A3));

        stop(A1);
        assertEquals(2L, submitUntilAccepted(2, 10_000));
        assertEquals(3L, submitUntilAccepted(3, 5_000));
        await("팀 리더가 꺼져도 팀원이 3번 블록까지", () -> sameHead(3, 1, 2, A2, A3));
        String signer = storedBlocks(A2).get(2).signerId();
        assertTrue(signer.equals("B1") || signer.equals("C1"), "다른 팀 리더의 사본: " + signer);

        start(A1);
        assertEquals(4L, submitUntilAccepted(4, 5_000));
        await("팀 리더가 돌아온 뒤에도 같은 체인", () -> sameHead(4, 0, 1, 2, A2, A3));
    }

    @Test
    void aMemberStartedLaterCatchesUp() {
        stop(A3);
        awaitKeys();
        for (int t = 1; t <= 5; t++) {
            assertEquals((long) t, submitUntilAccepted(t, 8_000));
        }
        start(A3);
        await("늦게 켠 팀원이 5번 블록까지", () -> sameHead(5, 0, A2, A3));
    }

    /**
     * 팀원 H2를 고치고 켜면 켤 때 검사(해시 · 리더 층 서명)에서 잡힌다. 팀원은 Raft 멤버가 아니라서 장부 전체(#1부터)가 달라도
     * 비우고 다시 받으면 된다 → 리더 층 판정(제외) → 복구 → 복귀. 다른 팀원은 그동안 그대로 받는다.
     */
    @Test
    void aTamperedMemberIsCaughtAtStartupThenExcludedAndRestored() {
        awaitKeys();
        assertEquals(1L, submitUntilAccepted(1, 8_000));
        await("팀원이 1번 블록", () -> sameHead(1, 0, A2, A3));

        stop(A2);
        new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(A2), "sa", ""))
                .update("update block set record_hash = ? where block_index = 1", "e".repeat(64));
        start(A2);

        assertEquals(2L, submitUntilAccepted(2, 5_000));
        await("정상 팀원 A3는 2번 블록", () -> sameHead(2, 0, A3));
        await("A2: 제외 → #1부터 다시 받아 복귀", () -> status(A1).verdicts().stream()
                .filter(v -> "A2".equals(v.suspectId())).map(v -> v.action()).toList().equals(List.of("EXCLUDED", "RESTORED")));
        await("복귀한 A2가 같은 체인 · 팀 리더가 OK로 봄", () -> sameHead(2, 0, A2, A3)
                && teamMember("A2").map(m -> m.state() == ReasonCode.OK).orElse(false));
        assertEquals(ReasonCode.OK, teamMember("A3").orElseThrow().state());
    }

    /** 팀원이 받는 블록마다 하는 검사: 서명 · 서명자 · 앞 블록 연결 */
    @Test
    void aMemberChecksSignatureAndPrevHashOfEveryBlock() {
        awaitKeys();
        assertEquals(1L, submitUntilAccepted(1, 8_000));
        await("팀원이 1번 블록", () -> sameHead(1, 0, A3));
        BlockDto first = storedBlocks(A1).get(0);
        NodeKeys a1 = new NodeKeys("A1", dir.resolve("A1.key"));      // 테스트에서만: 팀 리더의 키로 블록을 꾸며 본다
        com.fairchain.api.util.Sha256RecordHasher hasher = new com.fairchain.api.util.Sha256RecordHasher();

        // 앞 블록 해시가 다른 2번 블록 (서명은 진짜) → 갈라짐
        String wrongPrev = "f".repeat(64);
        BlockProposalDto forked = new BlockProposalDto(2L, wrongPrev, key(9), 1L, RECORD, 1000L,
                hasher.hash("2", wrongPrev, key(9), "1", RECORD));
        MemberAckDto ack = push(A3, a1.sign(forked));
        assertFalse(ack.stored());
        assertEquals(ReasonCode.DIVERGED, ack.reason());
        assertEquals(1L, ack.lastBlockIndex());
        assertEquals(first.blockHash(), ack.lastBlockHash());

        // 이어지는 블록이지만 서명을 바꿔치기 → 잘못된 블록
        BlockProposalDto next = new BlockProposalDto(2L, first.blockHash(), key(9), 1L, RECORD, 1000L,
                hasher.hash("2", first.blockHash(), key(9), "1", RECORD));
        BlockProposalDto signed = a1.sign(next);
        BlockProposalDto badSignature = new BlockProposalDto(2L, first.blockHash(), key(9), 1L, RECORD, 1000L,
                next.blockHash(), "A1", a1.sign(forked).signature());
        assertEquals(ReasonCode.INVALID_BLOCK, push(A3, badSignature).reason());

        // 리더 층 노드가 아닌 서명자 → 공개키를 모르므로 저장하지 않는다
        NodeKeys stranger = new NodeKeys("Z9", dir.resolve("Z9.key"));
        assertFalse(push(A3, stranger.sign(next)).stored());

        // 진짜 팀 리더 서명 + 이어지는 블록 → 저장함 (테스트용으로 꾸민 블록이라 이 팀원만 갖는다)
        MemberAckDto ok = push(A3, signed);
        assertTrue(ok.stored());
        assertEquals(2L, ok.lastBlockIndex());

        // 팀원 노드는 팀 리더 역할을, 리더 층 노드는 팀원 역할을 하지 않는다
        MembershipResultDto notLeader = http.post().uri(url(A3) + NodeTeamApi.MEMBERS)
                .body(new TeamHelloDto("A9", "A", "http://x", 0L, null, ReasonCode.OK)).retrieve().body(MembershipResultDto.class);
        assertFalse(notLeader.accepted());
        assertThrows(HttpClientErrorException.class, () -> push(1, signed));
        MembershipResultDto otherTeam = http.post().uri(url(1) + NodeTeamApi.MEMBERS)
                .body(new TeamHelloDto("A9", "A", "http://x", 0L, null, ReasonCode.OK)).retrieve().body(MembershipResultDto.class);
        assertFalse(otherTeam.accepted(), "B1은 팀 A의 팀 리더가 아니다");
    }

    private MemberAckDto push(int node, BlockProposalDto block) {
        return http.post().uri(url(node) + NodeMemberApi.BLOCKS).body(List.of(block)).retrieve().body(MemberAckDto.class);
    }
}
