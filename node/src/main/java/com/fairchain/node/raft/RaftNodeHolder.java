package com.fairchain.node.raft;

import com.fairchain.node.config.NodeSettings;
import com.fairchain.node.ledger.Ledger;
import com.fairchain.node.security.NodeKeys;
import com.fairchain.nodeapi.NodeConsensusApi;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.VerdictDto;
import io.microraft.Ordered;
import io.microraft.QueryPolicy;
import io.microraft.RaftConfig;
import io.microraft.RaftEndpoint;
import io.microraft.RaftNode;
import io.microraft.RaftNodeStatus;
import io.microraft.exception.CannotReplicateException;
import io.microraft.model.message.RaftMessage;
import io.microraft.persistence.RestoredRaftState;
import io.microraft.report.RaftGroupMembers;
import io.microraft.report.RaftTerm;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 이 노드의 MicroRaft 노드를 만들고 · 켜고 · 끈다. 리더 층 노드에서만 돈다 (팀원은 Raft 멤버가 아니다).
 *
 * <ul>
 *   <li>켤 때: H2 체인 전체 검사 → 디스크의 Raft 상태로 복원(없으면 node.members로 새 그룹) → 시작</li>
 *   <li>H2가 로그보다 뒤처지면: 다른 리더 층 노드에서 {@code GET /node/consensus/blocks}로 받아 채운다 (Fabric 방식)</li>
 *   <li>내 H2가 로그와 다르거나 제외가 기록되면: Raft 노드를 멈춘다 — 투표 · 후보 · 블록 제공을 하지 않는 "멈춘 노드"가 된다
 *       (계획 4.8). 멤버 명단에는 남는다 (제외 ≠ 해지)</li>
 * </ul>
 */
@Component
public class RaftNodeHolder implements BlockStateMachine.Listener {

    private static final Logger log = LoggerFactory.getLogger(RaftNodeHolder.class);
    public static final String GROUP_ID = "fairchain";
    private static final int CATCH_UP_PAGE = 200;

    private final NodeSettings settings;
    private final Ledger ledger;
    private final MemberRegistry registry;
    private final NodeKeys keys;
    private final RaftMessageCodec codec;
    private final ApplicationEventPublisher events;
    private final HttpRaftTransport transport;
    /** Raft 노드를 다시 만들 때마다 새로 만든다 (복귀 뒤 로그를 처음부터 다시 적용) */
    private volatile BlockStateMachine stateMachine;
    private final NodeEndpoint self;
    /** 이 번호까지의 Raft 로그에 있는 "나의 제외"는 이미 복구한 것 — 로그를 다시 적용할 때 무시한다 */
    private final File restoreMarkerFile;
    private volatile long restoredAt;
    /** 이 노드의 선거 시간 제한 (설정값 + 노드마다 다른 무작위 값) */
    private final long electionTimeoutMillis;
    private final RestClient http;
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ledger-catch-up");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean catchUpQueued = new AtomicBoolean();

    private volatile RaftNode node;
    private volatile DurableRaftStore store;
    /** null이 아니면 이 노드는 빠져 있다 (체인 손상 · 제외 판정) */
    private volatile String outReason;
    private final Object outLock = new Object();
    /** 내 H2 체인이 깨진 것을 확인함 (해시 · 서명 · 로그와 불일치). 복구 전까지 원장 상태에 chainValid=false로 보인다 */
    private volatile boolean chainBroken;
    /** 이 임기에서 리더가 블록을 만들 준비가 됐는지 (임기 첫 항목 확정 확인) */
    private volatile int readyTerm = -1;
    /** 이 노드가 리더 층에서 해지됐다 — 제외와 달리 돌아오지 않는다 (복구 · 복귀 요청을 하지 않는다) */
    private volatile boolean removedFromGroup;
    /** Raft 로그에서 이 노드의 해지 기록을 적용한 시각 (없으면 0). 조금 뒤 Raft를 멈춘다 */
    private volatile long removalSeenAt;
    /** 해지 기록을 적용한 뒤 Raft를 멈출 때까지 두는 시간 — 그동안은 멤버 변경이 확정되도록 응답을 계속 한다 */
    private static final long REMOVAL_GRACE_MILLIS = 2000;

    public RaftNodeHolder(NodeSettings settings, Ledger ledger, MemberRegistry registry, NodeKeys keys,
                          RaftMessageCodec codec, ApplicationEventPublisher events) {
        this.settings = settings;
        this.ledger = ledger;
        this.registry = registry;
        this.keys = keys;
        this.codec = codec;
        this.events = events;
        this.self = new NodeEndpoint(settings.id());
        Duration heartbeatTimeout = Duration.ofSeconds(settings.heartbeatTimeoutSecs());
        this.transport = new HttpRaftTransport(settings.id(), registry, codec, settings.secret(),
                settings.messageTimeout(), heartbeatTimeout, this::toldRemoved);
        this.stateMachine = new BlockStateMachine(ledger, registry, this, keys::sign);
        this.restoreMarkerFile = new File(settings.raftStoreFile().getPath() + ".restored");
        this.restoredAt = readRestoreMarker();
        // Raft §5.2: 선거 시간 제한을 노드마다 다르게 해서 두 후보가 계속 같이 출마하는 것(표 갈림)을 막는다.
        // MicroRaft는 선거마다 0~100ms만 더하므로, 노드마다 0~jitter ms를 한 번 더 정한다 (설정값이라 MicroRaft는 고치지 않는다)
        long jitter = settings.electionJitterMillis();
        this.electionTimeoutMillis = settings.electionTimeoutMillis()
                + (jitter > 0 ? java.util.concurrent.ThreadLocalRandom.current().nextLong(jitter + 1) : 0);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) settings.forwardTimeout().toMillis());
        factory.setReadTimeout((int) settings.forwardTimeout().toMillis());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    // ------------------------------------------------------------------ 켜고 끄기

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!settings.leaderLayer() || node != null) {
            return;
        }
        // 켤 때 전체 검사: 해시 연결 + 블록마다 내 서명 (리더 층 노드의 사본은 모두 자기가 서명했다)
        if (!ledger.audit(keys::signedByMe)) {
            chainInvalid("시작할 때 체인 전체 검사 실패: 해시 연결이 끊겼거나 내 서명과 맞지 않는 블록이 있다"
                    + " — 장부가 바뀌었거나 키 파일(" + settings.keyFile() + ")이 바뀌었다");
            return;
        }
        startRaft();
    }

    /** 복구를 마치고 리더가 복귀를 기록한 뒤 Raft에 다시 참여한다 (Raft 상태 파일은 그대로 — 미정사항 11번) */
    public synchronized void rejoin(long restoreLogIndex) {
        if (removedFromGroup) {
            return;     // 해지된 노드는 복귀하지 않는다
        }
        writeRestoreMarker(Math.max(restoredAt, restoreLogIndex));
        ledger.audit();
        chainBroken = false;
        synchronized (outLock) {
            outReason = null;
        }
        readyTerm = -1;
        if (settings.leaderLayer()) {
            RaftNode old = node;
            if (old != null && !RaftNodeStatus.isTerminal(old.getStatus())) {
                return;     // 이미 돌고 있음
            }
            node = null;
            startRaft();
            log.info("복귀 — Raft에 다시 참여한다 (복귀 기록 로그 #{})", restoreLogIndex);
        } else {
            log.info("복귀 — 팀원으로 다시 참여한다 (복귀 기록 로그 #{})", restoreLogIndex);
        }
    }

    private synchronized void startRaft() {
        if (node != null) {
            return;
        }
        stateMachine = new BlockStateMachine(ledger, registry, this, keys::sign);
        DurableRaftStore st = DurableRaftStore.open(settings.raftStoreFile(), codec);
        Optional<RestoredRaftState> restored = st.restoredState();
        RaftConfig config = RaftConfig.newBuilder()
                .setLeaderElectionTimeoutMillis(electionTimeoutMillis)
                .setLeaderHeartbeatPeriodSecs(settings.heartbeatSecs())
                .setLeaderHeartbeatTimeoutSecs(settings.heartbeatTimeoutSecs())
                .setCommitCountToTakeSnapshot(settings.snapshotEvery())
                .build();
        RaftNode.RaftNodeBuilder builder = RaftNode.newBuilder()
                .setGroupId(GROUP_ID)
                .setConfig(config)
                .setTransport(transport)
                .setStateMachine(stateMachine)
                .setStore(st)
                .setModelFactory(codec.factory());
        if (restored.isPresent()) {
            RaftEndpoint stored = restored.get().getLocalEndpointPersistentState().getLocalEndpoint();
            if (!self.equals(stored)) {
                st.close();
                throw new IllegalStateException("Raft state file " + settings.raftStoreFile() + " belongs to node "
                        + stored.getId() + ", not " + self.id());
            }
            builder.setRestoredState(restored.get());
            log.info("Raft 상태 복원: 임기 {}, 로그 {}개", restored.get().getTermPersistentState().getTerm(),
                    restored.get().getLogEntries().size());
        } else {
            List<RaftEndpoint> initial = new ArrayList<>();
            for (MemberRecord m : settings.members()) {
                initial.add(new NodeEndpoint(m.id()));
            }
            builder.setLocalEndpoint(self).setInitialGroupMembers(initial);
            log.info("새 Raft 그룹 멤버로 시작: {}", initial);
        }
        log.info("하트비트 {}초 · 하트비트 시간 제한 {}초 · 선거 시간 제한 {}ms", settings.heartbeatSecs(),
                settings.heartbeatTimeoutSecs(), electionTimeoutMillis);
        store = st;
        node = builder.build();
        node.start();
        startWatchdog();
    }

    // ------------------------------------------------------------------ 리더 장애 감지 보완

    private final java.util.concurrent.ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "raft-watchdog");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean watchdogStarted = new AtomicBoolean();
    private volatile long lastTrigger;

    private void startWatchdog() {
        if (watchdogStarted.compareAndSet(false, true)) {
            watchdog.scheduleWithFixedDelay(() -> {
                finishRemovalIfDue();
                checkLeaderSilence();
            }, 250, 250, TimeUnit.MILLISECONDS);
        }
    }

    // ------------------------------------------------------------------ 해지된 노드 (표준: 해지된 멤버는 멈춘다)

    /**
     * 해지된 노드는 Raft를 멈춘다 (etcd도 해지된 멤버는 스스로 꺼진다).
     *
     * 멈추지 않으면 생기는 일 (2026-11-25 실제로 확인): 해지된 노드는 리더가 더는 로그를 보내 주지 않아 자기가 해지된 줄 모르고,
     * 리더 소식이 끊겼다고 보고 계속 선거를 건다. 임기 번호만 끝없이 올라가다가, 남은 멤버가 잠깐 리더를 놓친 순간 그 높은 임기를
     * 받아들이면 멀쩡한 리더가 물러난다 (Raft 논문 4.2.3절의 "해지된 서버가 그룹을 방해하는 문제").
     *
     * 노드가 자기 해지를 아는 길은 셋이다:
     * <ol>
     *   <li>로그에서 자기 해지 기록(RemoveMember)을 적용했다 → 멤버 변경이 확정되도록 잠깐 기다린 뒤 멈춘다</li>
     *   <li>MicroRaft가 멤버 변경을 적용하고 스스로 멈췄다 (TERMINATED)</li>
     *   <li>남은 멤버가 이 노드의 Raft 메시지에 410으로 답했다 — 해지 기록을 못 받고 꺼져 있던 노드 ({@link #toldRemoved})</li>
     * </ol>
     */
    private void finishRemovalIfDue() {
        try {
            RaftNode n = node;
            if (n == null || removedFromGroup) {
                return;
            }
            long seen = removalSeenAt;
            boolean terminated = n.getStatus() == RaftNodeStatus.TERMINATED;
            if (terminated && outReason == null && (seen != 0 || registry.isRemoved(self.id()))) {
                leaveRemoved("Raft 로그에 이 노드의 해지가 기록됨");
            } else if (seen != 0 && System.currentTimeMillis() - seen > REMOVAL_GRACE_MILLIS
                    && !self.equals(n.getTerm().getLeaderEndpoint())) {
                // 리더 자신을 해지하는 경우는 멤버 변경을 끝낸 뒤 MicroRaft가 멈춘다 (위 TERMINATED)
                leaveRemoved("Raft 로그에 이 노드의 해지가 기록됨");
            }
        } catch (RuntimeException e) {
            log.debug("해지 확인 실패: {}", e.toString());
        }
    }

    /** 남은 멤버가 "너는 해지된 노드다"(HTTP 410)라고 답했다 */
    private void toldRemoved(String byPeer) {
        if (settings.leaderLayer() && !removedFromGroup) {
            leaveRemoved("리더 층 노드 " + byPeer + "가 이 노드는 해지됐다고 알려 줌");
        }
    }

    private void leaveRemoved(String why) {
        synchronized (outLock) {
            if (removedFromGroup) {
                return;
            }
            removedFromGroup = true;
            outReason = "해지됨 — " + why + ". 해지된 ID는 다시 쓰지 않는다: 이 프로세스를 끄고, 다시 넣으려면 새 ID로 새 노드를 켠다";
        }
        log.warn("이 노드는 리더 층에서 해지됐다 — {} (Raft를 멈춘다)", why);
        RaftNode n = node;
        if (n != null && n.getStatus() != RaftNodeStatus.TERMINATED) {
            n.terminate();
        }
        // NodeLeftEvent를 내지 않는다: 복구(#k부터 다시 받기 · 복귀 요청)는 제외된 노드가 하는 일이고, 해지된 노드는 돌아오지 않는다
    }

    /** 이 노드가 리더 층에서 해지됐는지 (제외와 달리 복귀하지 않는다) */
    public boolean removedFromGroup() {
        return removedFromGroup;
    }

    /**
     * 해지된 노드가 보낸 Raft 메시지인지. 받지 않고 410으로 답해 그 노드가 멈추게 한다 (etcd도 해지된 멤버의 연결을 거절한다).
     * 해지 기록이 적용됐더라도 멤버 변경이 아직 이 노드에 오지 않았으면(명단에 남아 있으면) 받는다 — 그 변경을 확정하는 데 필요하다.
     */
    public boolean rejectsSender(RaftEndpoint sender) {
        RaftNode n = node;
        if (n == null || sender == null || !running()) {
            return false;
        }
        return registry.isRemoved(String.valueOf(sender.getId())) && !n.getEffectiveMembers().getMembers().contains(sender);
    }

    /**
     * MicroRaft 0.7은 팔로워가 사전 투표(pre-vote)를 시작했다가 거절당한 뒤 같은 임기의 리더를 알게 되면 그 사전 투표 상태를
     * 지우지 않는다. 그 상태에서는 하트비트 검사(HeartbeatTask)가 건너뛰어져, 리더가 죽어도 이 노드는 선거를 시작하지 않는다
     * (선거 직후 · 재시작 직후에 자주 생겨 PC 1대 측정에서 새 리더까지 8초가 걸린 원인).
     *
     * 그래서 MicroRaft가 보고하는 "리더 하트비트를 마지막으로 받은 시각"이 시간 제한을 넘었는데도 이 노드가
     * 아직 팔로워이면, 리더십 이전 때 리더가 보내는 메시지(TriggerLeaderElectionRequest)를 이 노드에 넣어 표준 선거를 시작한다.
     * 표준 Raft가 이미 선거를 시작했어야 할 때에만 하므로 동작은 Raft 그대로이고, MicroRaft 코드는 고치지 않는다.
     */
    private void checkLeaderSilence() {
        try {
            RaftNode n = node;
            if (n == null || !running() || isLeader()) {
                return;
            }
            io.microraft.report.RaftNodeReport report = n.getReport().get(1, TimeUnit.SECONDS).getResult();
            RaftEndpoint leader = report.getTerm().getLeaderEndpoint();
            Optional<Long> heard = report.getLeaderHeartbeatTimestamp();
            if (report.getRole() != io.microraft.RaftRole.FOLLOWER || leader == null || heard.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            // 시간 제한이 지난 뒤면 표준 Raft에서도 선거를 시작할 때다. MicroRaft는 1초마다 검사해 4~5초에 시작하므로
            // 여기서는 시간 제한 + 0.3초에 본다 (막힌 노드만 아니면 보통 MicroRaft가 먼저 시작한다)
            // 노드마다 다른 무작위 값(선거 시간 제한에 더한 것)을 더해, 막힌 노드 둘이 같은 순간에 출마하지 않게 한다
            long limit = settings.heartbeatTimeoutSecs() * 1000 + 300 + (electionTimeoutMillis - settings.electionTimeoutMillis());
            if (now - heard.get() < limit || now - lastTrigger < electionTimeoutMillis + 500) {
                return;
            }
            lastTrigger = now;
            log.warn("리더 {}의 소식이 {}ms 없는데 선거가 시작되지 않음 — 선거를 시작한다", leader.getId(), now - heard.get());
            n.handle(codec.factory().createTriggerLeaderElectionRequestBuilder()
                    .setGroupId(GROUP_ID)
                    .setSender(leader)
                    .setTerm(report.getTerm().getTerm())
                    .setLastLogTerm(report.getLog().getLastLogOrSnapshotTerm())
                    .setLastLogIndex(report.getLog().getLastLogOrSnapshotIndex())
                    .build());
        } catch (Exception e) {
            log.debug("리더 장애 감지 보완 실패: {}", e.toString());
        }
    }

    @PreDestroy
    public synchronized void stop() {
        background.shutdownNow();
        watchdog.shutdownNow();
        RaftNode n = node;
        node = null;
        if (n != null) {
            try {
                n.terminate().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Raft 노드 종료 대기 실패: {}", e.toString());
            }
        }
        DurableRaftStore st = store;
        store = null;
        if (st != null && (n == null || n.getStatus() != RaftNodeStatus.TERMINATED)) {
            try {
                st.close();
            } catch (RuntimeException ignored) {
                // 이미 닫힘
            }
        }
    }

    // ------------------------------------------------------------------ 상태

    public NodeEndpoint self() {
        return self;
    }

    public MemberRegistry registry() {
        return registry;
    }

    public BlockStateMachine stateMachine() {
        return stateMachine;
    }

    /** 빠진 이유 (참여 중이면 null) */
    public String outReason() {
        return outReason;
    }

    /** 내 체인이 깨진 것을 확인했는지 (해시 · 서명 · Raft 로그와 불일치) */
    public boolean chainBroken() {
        return chainBroken;
    }

    public NodeKeys keys() {
        return keys;
    }

    public boolean running() {
        RaftNode n = node;
        return n != null && outReason == null && !RaftNodeStatus.isTerminal(n.getStatus());
    }

    public boolean isLeader() {
        RaftNode n = node;
        return running() && n != null && self.equals(n.getTerm().getLeaderEndpoint());
    }

    public Optional<String> leaderId() {
        RaftNode n = node;
        if (!running() || n == null) {
            return Optional.empty();
        }
        RaftEndpoint leader = n.getTerm().getLeaderEndpoint();
        return leader == null ? Optional.empty() : Optional.of(String.valueOf(leader.getId()));
    }

    public Optional<RaftTerm> term() {
        RaftNode n = node;
        return n == null ? Optional.empty() : Optional.of(n.getTerm());
    }

    public Optional<RaftGroupMembers> committedMembers() {
        RaftNode n = node;
        return n == null ? Optional.empty() : Optional.of(n.getCommittedMembers());
    }

    /** 확정 전인 멤버 변경까지 포함한 명단 — committed와 다르면 변경이 진행 중이다 */
    public Optional<RaftGroupMembers> effectiveMembers() {
        RaftNode n = node;
        return n == null ? Optional.empty() : Optional.of(n.getEffectiveMembers());
    }

    /**
     * Raft 멤버 변경 (MicroRaft {@code changeMembership}) — 한 번에 하나씩. expected는 지금 확정된 명단의 로그 번호로,
     * 그사이 다른 변경이 끼어들면 MicroRaft가 거부한다 (표준 단일 멤버 변경).
     */
    public RaftGroupMembers changeMembership(String id, io.microraft.MembershipChangeMode mode, long expected,
                                             Duration timeout) throws InterruptedException, ExecutionException, TimeoutException {
        RaftNode n = node;
        if (n == null) {
            throw new IllegalStateException("Raft node not running");
        }
        return n.changeMembership(new NodeEndpoint(id), mode, expected)
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS).getResult();
    }

    /** 리더가 본 팔로워 · learner의 로그 진행 (matchIndex) */
    public Optional<io.microraft.report.RaftNodeReport> report() {
        RaftNode n = node;
        if (n == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(n.getReport().get(2, TimeUnit.SECONDS).getResult());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ Raft 메시지 · 복제

    public void handle(RaftMessage message) {
        RaftNode n = node;
        if (n != null && outReason == null && GROUP_ID.equals(message.getGroupId())) {
            n.handle(message);
        }
    }

    /**
     * 리더가 블록을 만들어도 되는지. 새 리더는 자기 임기의 첫 항목이 확정된 뒤에야 앞선 블록이 모두 적용됐다고 확신할 수 있다
     * (Raft §8, 미정사항 3번). MicroRaft의 LINEARIZABLE 조회가 바로 그 조건을 확인한다 — 임기마다 한 번만 한다.
     */
    public boolean awaitReady(Duration timeout) throws InterruptedException {
        RaftNode n = node;
        if (n == null || !isLeader()) {
            return false;
        }
        int term = n.getTerm().getTerm();
        if (readyTerm == term) {
            return true;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                n.query(new LedgerQuery.Head(), QueryPolicy.LINEARIZABLE, Optional.empty(), Optional.empty())
                        .get(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS);
                readyTerm = term;
                return true;
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof CannotReplicateException)) {
                    return false;
                }
                Thread.sleep(20);
            } catch (TimeoutException e) {
                return false;
            }
        }
        return false;
    }

    /** 로그에 넣고 이 노드에서 적용될 때까지 기다린다 */
    public Object replicate(LedgerOp op, Duration timeout) throws InterruptedException, ExecutionException, TimeoutException {
        return replicateOrdered(op, timeout).getResult();
    }

    /** replicate와 같고, 기록된 Raft 로그 번호도 돌려준다 */
    public Ordered<Object> replicateOrdered(LedgerOp op, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        RaftNode n = node;
        if (n == null) {
            throw new IllegalStateException("Raft node not running");
        }
        return n.<Object>replicate(op).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ 상태 기계가 알려 주는 일

    @Override
    public void chainInvalid(String why) {
        chainBroken = true;
        leave("체인 손상 (CHAIN_INVALID): " + why, null);
    }

    @Override
    public void excluded(String nodeId, long logIndex, VerdictDto verdict) {
        if (self.id().equals(nodeId) && logIndex > restoredAt) {
            leave("판정으로 제외됨 (Raft 로그 #" + logIndex + ", 갈라진 블록 #"
                    + (verdict == null ? "?" : verdict.blockIndex()) + ")", verdict);
        }
    }

    @Override
    public void restored(String nodeId, long logIndex) {
        if (self.id().equals(nodeId) && logIndex > restoredAt) {
            writeRestoreMarker(logIndex);
        }
    }

    @Override
    public void removed(String nodeId, long logIndex) {
        if (self.id().equals(nodeId) && removalSeenAt == 0) {
            removalSeenAt = System.currentTimeMillis();
            log.warn("이 노드의 해지가 Raft 로그 #{}에 기록됨 — 멤버 변경이 끝나면 Raft를 멈춘다", logIndex);
        }
    }

    @Override
    public void snapshotInstalled(long logIndex) {
        if (registry.isRemoved(self.id()) && removalSeenAt == 0) {
            removalSeenAt = System.currentTimeMillis();     // 스냅샷으로 받은 명단에 내 해지가 들어 있다
        }
        Optional<Long> at = registry.excludedAt(self.id());
        if (at.isPresent() && at.get() > restoredAt) {
            leave("판정으로 제외됨 (스냅샷에 기록, Raft 로그 #" + at.get() + ")",
                    registry.lastVerdict(self.id(), "EXCLUDED").orElse(null));
        }
    }

    /** 팀원이 리더 층의 제외 명단에서 자기를 봤을 때 */
    public void memberExcluded(VerdictDto verdict) {
        leave("판정으로 제외됨 (갈라진 블록 #" + (verdict == null ? "?" : verdict.blockIndex()) + ")", verdict);
    }

    public long restoredAt() {
        return restoredAt;
    }

    private long readRestoreMarker() {
        try {
            return restoreMarkerFile.exists() ? Long.parseLong(Files.readString(restoreMarkerFile.toPath()).trim()) : 0;
        } catch (IOException | NumberFormatException e) {
            log.warn("복귀 표시 파일을 읽지 못함 ({}): {}", restoreMarkerFile, e.toString());
            return 0;
        }
    }

    private void writeRestoreMarker(long logIndex) {
        synchronized (restoreMarkerFile) {
            if (!settings.leaderLayer() || logIndex <= restoredAt) {
                return;
            }
            restoredAt = logIndex;
            try {
                File parent = restoreMarkerFile.getAbsoluteFile().getParentFile();
                if (parent != null) {
                    Files.createDirectories(parent.toPath());
                }
                Files.writeString(restoreMarkerFile.toPath(), Long.toString(logIndex));
            } catch (IOException e) {
                log.warn("복귀 표시 파일을 쓰지 못함 ({}): {}", restoreMarkerFile, e.toString());
            }
        }
    }

    @Override
    public void blockStored(long index) {
        events.publishEvent(new BlockStoredEvent(index));
    }

    /**
     * Raft 노드를 멈춘다 — 투표 · 후보 · 블록 제공을 하지 않는 "멈춘 노드" (계획 4.8). 멤버 명단에는 남는다.
     * 그다음 복구(#k부터 다시 받기)는 RecoveryService · MemberReplicaService가 한다.
     * Raft 스레드에서 불릴 수 있으므로 기다리지 않는다.
     */
    private void leave(String why, VerdictDto verdict) {
        synchronized (outLock) {
            if (outReason != null) {
                return;
            }
            outReason = why;
        }
        log.error("이 노드는 {}에서 빠진다 — {}", settings.leaderLayer() ? "리더 층" : "팀 사본", why);
        RaftNode n = node;
        if (n != null) {
            n.terminate();
        }
        events.publishEvent(new NodeLeftEvent(why, verdict));
    }

    /** 복구 중 상태 표시를 바꾼다 (빠져 있는 동안만) */
    public void updateOutReason(String why) {
        if (outReason != null) {
            outReason = why;
        }
    }

    @Override
    public void catchUpNeeded() {
        if (catchUpQueued.compareAndSet(false, true)) {
            try {
                background.submit(this::catchUp);
            } catch (RuntimeException e) {
                catchUpQueued.set(false);
            }
        }
    }

    /** H2가 Raft 로그의 마지막 블록까지 오도록 다른 리더 층 노드에서 블록을 받는다 */
    private void catchUp() {
        catchUpQueued.set(false);
        for (int round = 0; round < 60 && outReason == null && !Thread.currentThread().isInterrupted(); round++) {
            Ledger.Head target = stateMachine.logHead();
            Ledger.Head have = ledger.head();
            if (have.index() >= target.index()) {
                if (ledger.block(target.index()).map(b -> b.blockHash.equals(target.hash())).orElse(target.index() == 0)) {
                    return;
                }
                chainInvalid("따라잡은 블록 #" + target.index() + "가 Raft 로그와 다름");
                return;
            }
            boolean progressed = false;
            for (String peer : catchUpSources()) {
                if (pullFrom(peer, target)) {
                    progressed = true;
                    break;
                }
            }
            if (!progressed) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 리더를 먼저, 그다음 다른 멤버 (나와 제외된 노드는 빼고) */
    private List<String> catchUpSources() {
        List<String> ids = new ArrayList<>();
        leaderId().filter(id -> !id.equals(self.id())).ifPresent(ids::add);
        for (MemberRecord m : registry.all()) {
            if (!m.id().equals(self.id()) && !ids.contains(m.id()) && !registry.isExcluded(m.id())) {
                ids.add(m.id());
            }
        }
        return ids;
    }

    private boolean pullFrom(String peerId, Ledger.Head target) {
        String url = registry.urlOf(peerId).orElse(null);
        if (url == null) {
            return false;
        }
        long start = ledger.head().index();
        try {
            BlockProposalDto[] page = http.get()
                    .uri(url + NodeConsensusApi.BLOCKS + "?from=" + (start + 1) + "&limit=" + CATCH_UP_PAGE)
                    .header(NodeHeaders.NODE_SECRET, settings.secret())
                    .retrieve().body(BlockProposalDto[].class);
            int added = 0;
            String peerKey = registry.get(peerId).map(MemberRecord::publicKey).orElse(null);
            for (BlockProposalDto b : page == null ? List.<BlockProposalDto>of() : Arrays.asList(page)) {
                if (b.blockIndex() == null || b.blockIndex() > target.index()) {
                    break;
                }
                // 보낸 노드의 서명 확인 → 맞으면 내 키로 서명해 내 사본에 쓴다 (Fabric: 사본마다 쓴 노드가 서명)
                if (!peerId.equals(b.signerId()) || (peerKey != null && !NodeKeys.verify(peerKey, b))) {
                    log.warn("노드 {}에서 받은 블록 #{}의 서명이 맞지 않음 — 다른 노드에서 받는다", peerId, b.blockIndex());
                    return false;
                }
                if (peerKey == null) {
                    // 그룹이 막 만들어져 공개키가 아직 로그에 등록되지 않은 경우뿐. 마지막에 Raft 로그의 해시와 맞춰 본다
                    log.warn("노드 {}의 공개키가 아직 등록되지 않아 블록 #{}의 서명을 확인하지 못함", peerId, b.blockIndex());
                }
                Ledger.Append r = ledger.append(keys.sign(b));
                if (r == Ledger.Append.APPENDED) {
                    added++;
                } else if (r != Ledger.Append.ALREADY_HAVE) {
                    log.warn("노드 {}에서 받은 블록 #{}를 붙이지 못함: {}", peerId, b.blockIndex(), r);
                    break;
                }
                if (Objects.equals(b.blockIndex(), target.index()) && !target.hash().equals(b.blockHash())) {
                    log.warn("노드 {}의 블록 #{}가 Raft 로그와 다름 — 받은 블록을 지우고 다른 노드에서 다시 받는다", peerId, b.blockIndex());
                    ledger.truncateFrom(start + 1);
                    return false;
                }
            }
            if (added > 0) {
                log.info("노드 {}에서 블록 {}개를 받아 따라잡음 (높이 {})", peerId, added, ledger.head().index());
                events.publishEvent(new BlockStoredEvent(ledger.head().index()));
            }
            return added > 0;
        } catch (Exception e) {
            log.debug("노드 {}에서 블록 받기 실패: {}", peerId, e.toString());
            return false;
        }
    }
}
