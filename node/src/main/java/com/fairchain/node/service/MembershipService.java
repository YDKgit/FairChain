package com.fairchain.node.service;

import com.fairchain.node.config.NodeSettings;
import com.fairchain.node.raft.ApplyResult;
import com.fairchain.node.raft.LedgerOp;
import com.fairchain.node.raft.MemberRecord;
import com.fairchain.node.raft.MemberRegistry;
import com.fairchain.node.raft.NodeEndpoint;
import com.fairchain.node.raft.RaftNodeHolder;
import com.fairchain.node.security.NodeKeys;
import com.fairchain.nodeapi.NodeConsensusApi;
import com.fairchain.nodeapi.dto.MemberJoinDto;
import com.fairchain.nodeapi.dto.MembershipResultDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 멤버 명단 관리. 지금은 공개키 등록(3단계), 6단계에서 팀 가입 · 해지가 여기에 붙는다.
 *
 * 공개키 등록: Raft 멤버가 된 노드는 자기 공개키가 명단에 없으면 리더에게 등록을 요청한다 (1초마다 확인).
 * 리더는 Raft 로그에 RegisterMember로 기록하고, 모든 리더 층 노드 · 팀원은 이 명단으로 서명을 확인한다 (미정사항 9번).
 */
@Service
public class MembershipService {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);
    private static final Duration READY_TIMEOUT = Duration.ofMillis(1000);

    private final RaftNodeHolder raft;
    private final MemberRegistry registry;
    private final NodeSettings settings;
    private final NodeKeys keys;
    private final PeerClient peers;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "key-registration");
        t.setDaemon(true);
        return t;
    });
    private volatile String lastProblem;

    public MembershipService(RaftNodeHolder raft, MemberRegistry registry, NodeSettings settings, NodeKeys keys,
                             PeerClient peers) {
        this.raft = raft;
        this.registry = registry;
        this.settings = settings;
        this.keys = keys;
        this.peers = peers;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startSelfRegistration() {
        if (settings.leaderLayer()) {
            scheduler.scheduleWithFixedDelay(this::registerSelfIfNeeded, 500, 1000, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    /** 내 공개키가 명단에 없으면 리더에게 등록 요청 */
    void registerSelfIfNeeded() {
        try {
            if (!raft.running() || raft.leaderId().isEmpty()) {
                return;
            }
            Optional<MemberRecord> me = registry.get(settings.id());
            if (me.isEmpty() || keys.publicKey().equals(me.get().publicKey())) {
                return;     // 명단에 없는 노드(가입 전)거나 이미 등록됨
            }
            MembershipResultDto r = registerKey(new MemberJoinDto(settings.id(), me.get().team(), me.get().url(),
                    keys.publicKey()), false);
            if (r.accepted()) {
                log.info("공개키를 멤버 명단에 등록 요청함: {}", r.message());
                lastProblem = null;
            } else if (!Objects.equals(lastProblem, r.message())) {
                lastProblem = r.message();
                log.warn("공개키 등록이 아직 안 됨: {}", r.message());
            }
        } catch (RuntimeException e) {
            log.debug("공개키 등록 확인 실패: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ 팀 가입 · 해지 (미정사항 1 · 15번, 6단계)

    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");
    private static final Duration CHANGE_TIMEOUT = Duration.ofSeconds(10);
    private static final long CATCH_UP_MILLIS = 60_000;
    private final Object changeLock = new Object();
    private volatile boolean changing;

    /**
     * 가입 (표준 Raft 멤버 변경, MicroRaft): 멤버 명단에 노드(주소 · 팀 · 공개키) 등록 → learner로 추가 → 로그와 블록을 다
     * 따라잡으면 투표 멤버로 승격. 한 번에 하나씩. 공개키를 안 주면 새 노드의 상태 조회에서 받는다.
     */
    public MembershipResultDto addMember(MemberJoinDto dto, boolean forwarded) {
        if (dto == null || dto.id() == null || !ID.matcher(dto.id()).matches() || dto.url() == null || dto.url().isBlank()
                || dto.team() == null || dto.team().isBlank()) {
            return new MembershipResultDto(false, "노드 ID · 팀 · 주소가 필요하다");
        }
        if (!raft.running()) {
            return new MembershipResultDto(false, "이 노드는 리더 층 Raft에 참여하고 있지 않다");
        }
        if (!raft.isLeader()) {
            if (forwarded) {
                return new MembershipResultDto(false, "리더가 아니다 (NOT_LEADER)");
            }
            return toLeader(leader -> peers.forward(leader, com.fairchain.nodeapi.NodeAdminApi.MEMBERS, dto,
                    MembershipResultDto.class));
        }
        synchronized (changeLock) {
            String busy = busy();
            if (busy != null) {
                return new MembershipResultDto(false, busy);
            }
            changing = true;
        }
        try {
            String url = dto.url().replaceAll("/+$", "");
            String publicKey = dto.publicKey() != null ? dto.publicKey() : fetchPublicKey(url, dto.id());
            if (NodeKeys.parsePublicKey(publicKey) == null) {
                return new MembershipResultDto(false, dto.id() + "의 공개키를 받지 못함 — 노드가 켜져 있는지, ID가 맞는지 확인");
            }
            String registered = registry.get(dto.id()).map(MemberRecord::publicKey).orElse(null);
            if (registered != null && !registered.equals(publicKey)) {
                return new MembershipResultDto(false, dto.id() + "에는 다른 공개키가 등록돼 있다 — 해지 뒤 새 키로 재가입");
            }
            if (!raft.awaitReady(READY_TIMEOUT)) {
                return new MembershipResultDto(false, "리더가 아직 준비되지 않음");
            }
            NodeEndpoint endpoint = new NodeEndpoint(dto.id());
            var committed = raft.committedMembers().orElseThrow();
            if (committed.getVotingMembers().contains(endpoint)) {
                return new MembershipResultDto(true, dto.id() + "는 이미 투표 멤버");
            }
            // 1) 멤버 명단(공개키 포함)을 Raft 로그에 — 주소를 알아야 Raft 메시지를 보낼 수 있다
            raft.replicate(new LedgerOp.RegisterMember(dto.id(), dto.team().trim(), url, publicKey), settings.commitTimeout());
            // 2) learner로 추가: 투표 없이 로그 · 블록만 받는다
            if (!committed.getMembers().contains(endpoint)) {
                raft.changeMembership(dto.id(), io.microraft.MembershipChangeMode.ADD_LEARNER, committed.getLogIndex(),
                        CHANGE_TIMEOUT);
                log.info("{} learner로 추가 — 따라잡기를 기다린다", dto.id());
            }
            // 3) 다 따라잡으면 승격
            if (!awaitCaughtUp(endpoint, url)) {
                return new MembershipResultDto(false, dto.id() + "가 " + CATCH_UP_MILLIS / 1000
                        + "초 안에 따라잡지 못함 — learner로 남아 있다. 다시 요청하면 승격을 이어서 한다");
            }
            var now = raft.committedMembers().orElseThrow();
            raft.changeMembership(dto.id(), io.microraft.MembershipChangeMode.ADD_OR_PROMOTE_TO_FOLLOWER, now.getLogIndex(),
                    CHANGE_TIMEOUT);
            log.info("{} (팀 {}) 투표 멤버로 승격", dto.id(), dto.team());
            return new MembershipResultDto(true, dto.id() + " (팀 " + dto.team().trim() + ") 가입: learner → 투표 멤버");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MembershipResultDto(false, "중단됨");
        } catch (Exception e) {
            return new MembershipResultDto(false, "멤버 변경 실패: " + rootMessage(e));
        } finally {
            changing = false;
        }
    }

    /**
     * 해지: 명단에서 빼고 Raft 멤버에서 뺀다. 남는 팀(투표 멤버의 팀)이 3개 미만이 되면 거부 (2:1 판정 · 1팀 장애 허용).
     * 키 교체도 이 길이다 — 해지 뒤 새 키로 재가입 (미정사항 9번).
     */
    public MembershipResultDto removeMember(String id, boolean forwarded) {
        if (id == null || !ID.matcher(id).matches()) {
            return new MembershipResultDto(false, "노드 ID가 필요하다");
        }
        if (!raft.running()) {
            return new MembershipResultDto(false, "이 노드는 리더 층 Raft에 참여하고 있지 않다");
        }
        if (!raft.isLeader()) {
            if (forwarded) {
                return new MembershipResultDto(false, "리더가 아니다 (NOT_LEADER)");
            }
            return toLeader(leader -> peers.forwardDelete(leader, com.fairchain.nodeapi.NodeAdminApi.memberPath(id),
                    MembershipResultDto.class));
        }
        synchronized (changeLock) {
            String busy = busy();
            if (busy != null) {
                return new MembershipResultDto(false, busy);
            }
            changing = true;
        }
        try {
            var committed = raft.committedMembers().orElseThrow();
            NodeEndpoint endpoint = new NodeEndpoint(id);
            if (!committed.getMembers().contains(endpoint)) {
                return new MembershipResultDto(false, id + "는 리더 층 멤버가 아니다");
            }
            java.util.Set<String> teamsLeft = new java.util.HashSet<>();
            for (var m : committed.getVotingMembers()) {
                String mid = String.valueOf(m.getId());
                if (!mid.equals(id)) {
                    teamsLeft.add(registry.get(mid).map(MemberRecord::team).orElse(mid));
                }
            }
            if (teamsLeft.size() < 3) {
                return new MembershipResultDto(false, id + "를 빼면 팀이 " + teamsLeft.size() + "개 — 3개 미만이 되는 해지는 막는다");
            }
            if (!raft.awaitReady(READY_TIMEOUT)) {
                return new MembershipResultDto(false, "리더가 아직 준비되지 않음");
            }
            raft.replicate(new LedgerOp.RemoveMember(id), settings.commitTimeout());
            raft.changeMembership(id, io.microraft.MembershipChangeMode.REMOVE_MEMBER, committed.getLogIndex(), CHANGE_TIMEOUT);
            log.info("{} 해지 — 남은 팀 {}", id, teamsLeft);
            return new MembershipResultDto(true, id + " 해지 (남은 팀: " + String.join(", ", new java.util.TreeSet<>(teamsLeft)) + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MembershipResultDto(false, "중단됨");
        } catch (Exception e) {
            return new MembershipResultDto(false, "멤버 변경 실패: " + rootMessage(e));
        } finally {
            changing = false;
        }
    }

    /**
     * 리더에게 넘긴다. 그사이 리더가 바뀌어 "리더가 아니다"가 돌아오면 지금 리더를 다시 확인해 다시 넘긴다 (3번까지, 약 1초 간격) —
     * 운영자가 요청을 다시 보내는 것과 같다
     */
    private MembershipResultDto toLeader(java.util.function.Function<String, Optional<MembershipResultDto>> send) {
        MembershipResultDto last = new MembershipResultDto(false, "리더가 아니다 (NOT_LEADER)");
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return last;
                }
            }
            if (raft.isLeader()) {
                return new MembershipResultDto(false, "이 노드가 방금 리더가 됐다 — 다시 요청");
            }
            String leader = raft.leaderId().orElse(null);
            if (leader == null) {
                continue;
            }
            last = send.apply(leader).orElse(new MembershipResultDto(false, "리더 " + leader + "에게 전달하지 못함"));
            if (last.accepted() || !last.message().contains("NOT_LEADER")) {
                return last;
            }
        }
        return last;
    }

    /** 다른 멤버 변경이 진행 중이면 그 설명 (표준: 한 번에 하나씩) */
    private String busy() {
        if (changing) {
            return "다른 멤버 변경이 진행 중이다 — 끝난 뒤 다시 요청";
        }
        var committed = raft.committedMembers().orElse(null);
        var effective = raft.effectiveMembers().orElse(null);
        if (committed != null && effective != null && committed.getLogIndex() != effective.getLogIndex()) {
            return "Raft 멤버 변경이 아직 확정되지 않았다 — 끝난 뒤 다시 요청";
        }
        return null;
    }

    /** learner가 리더의 로그(matchIndex ≥ commitIndex)와 블록(H2 높이)을 다 따라잡을 때까지 */
    private boolean awaitCaughtUp(NodeEndpoint endpoint, String url) throws InterruptedException {
        long deadline = System.currentTimeMillis() + CATCH_UP_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            var report = raft.report().orElse(null);
            if (report != null) {
                Long match = report.getLog().getFollowerMatchIndices().get(endpoint);
                long commit = report.getLog().getCommitIndex();
                if (match != null && match >= commit && heightOf(url) >= ledgerHeight()) {
                    return true;
                }
            }
            Thread.sleep(200);
        }
        return false;
    }

    private long ledgerHeight() {
        return raft.stateMachine().logHead().index();
    }

    private long heightOf(String url) {
        try {
            var s = statusClient.get().uri(url + com.fairchain.nodeapi.NodeAuditApi.LEDGER_STATUS)
                    .header(com.fairchain.nodeapi.NodeHeaders.NODE_SECRET, settings.secret())
                    .retrieve().body(com.fairchain.nodeapi.dto.NodeLedgerStatusDto.class);
            return s == null || s.height() == null ? -1 : s.height();
        } catch (Exception e) {
            return -1;
        }
    }

    private String fetchPublicKey(String url, String id) {
        try {
            var s = statusClient.get().uri(url + com.fairchain.nodeapi.NodeAdminApi.STATUS)
                    .header(com.fairchain.nodeapi.NodeHeaders.NODE_SECRET, settings.secret())
                    .retrieve().body(com.fairchain.nodeapi.dto.NodeStatusDto.class);
            return s != null && id.equals(s.id()) ? s.publicKey() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    private final org.springframework.web.client.RestClient statusClient = org.springframework.web.client.RestClient.builder()
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                {
                    setConnectTimeout(1500);
                    setReadTimeout(3000);
                }
            }).build();

    /**
     * 공개키 등록 요청 처리 — 리더가 Raft 로그에 기록한다. 리더가 아니면 리더에게 한 번 넘긴다.
     *
     * @param forwarded 다른 노드가 넘겨준 요청이면 true
     */
    public MembershipResultDto registerKey(MemberJoinDto dto, boolean forwarded) {
        if (dto == null || dto.id() == null || NodeKeys.parsePublicKey(dto.publicKey()) == null) {
            return new MembershipResultDto(false, "노드 ID와 Ed25519 공개키(X.509, Base64)가 필요하다");
        }
        if (!raft.running()) {
            return new MembershipResultDto(false, "이 노드는 리더 층 Raft에 참여하고 있지 않다");
        }
        if (!raft.isLeader()) {
            String leader = raft.leaderId().orElse(null);
            if (forwarded || leader == null) {
                return new MembershipResultDto(false, "리더가 아니다 (NOT_LEADER)");
            }
            return peers.forward(leader, NodeConsensusApi.KEYS, dto, MembershipResultDto.class)
                    .orElse(new MembershipResultDto(false, "리더 " + leader + "에게 전달하지 못함"));
        }
        boolean member = raft.committedMembers()
                .map(m -> m.getMembers().contains(new NodeEndpoint(dto.id()))).orElse(false);
        if (!member) {
            return new MembershipResultDto(false, dto.id() + "는 리더 층 Raft 멤버가 아니다 — 가입은 /node/admin/members");
        }
        Optional<MemberRecord> existing = registry.get(dto.id());
        String registered = existing.map(MemberRecord::publicKey).orElse(null);
        if (dto.publicKey().equals(registered)) {
            return new MembershipResultDto(true, dto.id() + " 공개키는 이미 등록됨");
        }
        if (registered != null) {
            return new MembershipResultDto(false, dto.id() + "에는 다른 공개키가 등록돼 있다 — 키 교체는 해지 후 새 키로 재가입");
        }
        // 주소 · 팀은 이미 명단에 있는 값을 그대로 둔다 (공개키만 더한다)
        String team = existing.map(MemberRecord::team).orElse(dto.team());
        String url = existing.map(MemberRecord::url).orElse(dto.url());
        try {
            if (!raft.awaitReady(READY_TIMEOUT)) {
                return new MembershipResultDto(false, "리더가 아직 준비되지 않음");
            }
            Object result = raft.replicate(new LedgerOp.RegisterMember(dto.id(), team, url, dto.publicKey()),
                    settings.commitTimeout());
            if (result instanceof ApplyResult r && r.ok()) {
                return new MembershipResultDto(true, dto.id() + " 공개키 등록됨");
            }
            return new MembershipResultDto(false, dto.id() + " 공개키 등록 거부: " + result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MembershipResultDto(false, "중단됨");
        } catch (Exception e) {
            return new MembershipResultDto(false, "Raft 로그에 기록하지 못함: " + e.getClass().getSimpleName());
        }
    }
}
