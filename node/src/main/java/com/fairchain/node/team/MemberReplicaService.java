package com.fairchain.node.team;

import com.fairchain.node.config.NodeSettings;
import com.fairchain.node.ledger.Ledger;
import com.fairchain.node.raft.MemberRecord;
import com.fairchain.node.raft.MemberRegistry;
import com.fairchain.node.raft.RaftNodeHolder;
import com.fairchain.node.security.NodeKeys;
import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.NodeConsensusApi;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.NodeTeamApi;
import com.fairchain.node.service.PeerClient;
import com.fairchain.nodeapi.NodeReportApi;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.DivergenceReportDto;
import com.fairchain.nodeapi.dto.ReportAckDto;
import com.fairchain.nodeapi.dto.RestoreRequestDto;
import com.fairchain.nodeapi.dto.RestoreResultDto;
import com.fairchain.nodeapi.dto.VerdictDto;
import com.fairchain.nodeapi.dto.MemberAckDto;
import com.fairchain.nodeapi.dto.MemberInfoDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import com.fairchain.nodeapi.dto.ReasonCode;
import com.fairchain.nodeapi.dto.TeamHelloDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 팀원(node.role=member): 투표하지 않는 사본 (11/12 답변 2번, Fabric 피어 · Raft learner와 같은 자리).
 *
 * <ul>
 *   <li>팀 리더가 보내는 확정 블록을 받아 번호 · prevBlockHash · blockHash · 서명을 확인하고 저장한 뒤,
 *       {@code 저장함} 또는 거부 이유와 자기 마지막 번호 · 해시를 돌려준다</li>
 *   <li>1초마다 팀 리더에 등록 · 상태를 알리고, 빠진 블록을 받아 온다. 팀 리더가 꺼져 있으면 다른 팀 리더에게서 받는다 —
 *       서명을 확인하므로 누구에게서 받아도 안전하다</li>
 *   <li>서명 확인에 쓰는 공개키 명단은 블록과 같은 길(리더 층 노드)에서 받는다 (11/12 답변 9번)</li>
 *   <li>팀원은 Raft 멤버가 아니므로 과반에 들어가지 않는다 — 팀원 수는 0 · 1 · 2대 모두 괜찮다</li>
 * </ul>
 */
@Service
public class MemberReplicaService {

    private static final Logger log = LoggerFactory.getLogger(MemberReplicaService.class);
    private static final int PAGE = 200;

    private final Ledger ledger;
    private final MemberRegistry registry;
    private final NodeSettings settings;
    private final RaftNodeHolder raft;
    private final RestClient http;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "member-sync");
        t.setDaemon(true);
        return t;
    });
    /** 켤 때 서명까지 보는 전체 검사를 했는지 (공개키를 받아 와야 할 수 있다) */
    private volatile boolean audited;
    private volatile long keysFetchedAt;
    private volatile String lastSource;
    /** #k부터 다시 받는 중 — 이때만 빠진 상태에서도 블록을 저장한다 */
    private volatile boolean recovering;
    private volatile long lastSelfReport;
    private final PeerClient peers;

    public MemberReplicaService(Ledger ledger, MemberRegistry registry, NodeSettings settings, RaftNodeHolder raft,
                                PeerClient peers) {
        this.ledger = ledger;
        this.registry = registry;
        this.settings = settings;
        this.raft = raft;
        this.peers = peers;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(2000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!settings.leaderLayer()) {
            log.info("팀원 {} — 팀 {}의 팀 리더 {}에게서 블록을 받는다", settings.id(), settings.team(), settings.teamLeader());
            scheduler.scheduleWithFixedDelay(this::sync, 0, settings.memberSyncMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    public boolean chainBroken() {
        return raft.chainBroken();
    }

    // ------------------------------------------------------------------ 팀 리더 → 팀원: 블록 받기

    public MemberAckDto receive(List<BlockProposalDto> blocks) {
        if (blocks != null) {
            for (BlockProposalDto b : blocks) {
                ReasonCode r = store(b);
                if (r != ReasonCode.OK) {
                    if (r == ReasonCode.BEHIND) {
                        scheduler.execute(this::sync);       // 빠진 블록을 바로 받으러 간다
                    }
                    return ack(false, r);
                }
            }
        }
        return ack(true, ReasonCode.OK);
    }

    private MemberAckDto ack(boolean stored, ReasonCode reason) {
        Ledger.Head head = ledger.head();
        return new MemberAckDto(stored, reason, head.index(), head.hash());
    }

    /** 블록 하나 확인 · 저장. 받은 서명(리더 층 노드의 서명)을 그대로 둔다 */
    ReasonCode store(BlockProposalDto b) {
        if (raft.outReason() != null && !recovering) {
            return ReasonCode.CHAIN_INVALID;        // 빠진 팀원은 복구 전까지 블록을 받지 않는다
        }
        if (b == null || !ledger.wellFormed(b)) {
            return ReasonCode.INVALID_BLOCK;
        }
        String key = keyOf(b.signerId());
        if (key == null) {
            refreshKeys(true);
            key = keyOf(b.signerId());
        }
        if (key == null) {
            log.warn("블록 #{}에 서명한 {}의 공개키를 아직 모른다 — 나중에 다시 받는다", b.blockIndex(), b.signerId());
            return ReasonCode.BEHIND;
        }
        if (!NodeKeys.verify(key, b)) {
            log.warn("블록 #{}의 서명({})이 맞지 않음 — 저장하지 않는다", b.blockIndex(), b.signerId());
            return ReasonCode.INVALID_BLOCK;
        }
        return switch (ledger.append(b)) {
            case APPENDED, ALREADY_HAVE -> ReasonCode.OK;
            case GAP -> ReasonCode.BEHIND;
            case CONFLICT -> ReasonCode.DIVERGED;
            case INVALID -> ReasonCode.INVALID_BLOCK;
            case BROKEN -> {
                raft.chainInvalid("팀원 체인 검사 실패 (블록 #" + b.blockIndex() + "를 붙이기 전)");
                yield ReasonCode.CHAIN_INVALID;
            }
        };
    }

    /** 리더 층 멤버의 공개키 (리더 층 노드가 아닌 서명자는 인정하지 않는다) */
    private String keyOf(String signerId) {
        return signerId == null ? null : registry.get(signerId).map(MemberRecord::publicKey).orElse(null);
    }

    // ------------------------------------------------------------------ 1초마다: 등록 · 상태 알림 · 빠진 블록 받기

    void sync() {
        try {
            refreshKeys(false);
            if (registry.isExcluded(settings.id()) && raft.outReason() == null) {
                raft.memberExcluded(registry.lastVerdict(settings.id(), "EXCLUDED").orElse(null));
            }
            if (raft.outReason() != null) {
                hello();
                recoverIfPossible();
                return;
            }
            if (!audited && keysKnown()) {
                audited = true;
                if (!ledger.audit(this::signedByLeaderLayer)) {
                    raft.chainInvalid("팀원 켤 때 체인 전체 검사 실패 (해시 또는 리더 층 서명)");
                    return;
                }
            }
            boolean leaderUp = hello();
            pull(leaderUp);
        } catch (RuntimeException e) {
            log.debug("팀원 동기화 실패: {}", e.toString());
        }
    }

    private boolean signedByLeaderLayer(BlockProposalDto b) {
        String key = keyOf(b.signerId());
        return key != null && NodeKeys.verify(key, b);
    }

    // ------------------------------------------------------------------ 제외된 팀원의 복구 (미정사항 10 · 11번)

    /**
     * 판정(#k)을 알면 #k부터 지우고 리더 층에서 서명을 확인하며 다시 받은 뒤 복귀를 요청한다.
     * 판정이 없으면(스스로 체인 손상을 발견) 리더 층에 자기 자신을 신고한다 — 팀 리더도 같은 신고를 올린다.
     */
    private void recoverIfPossible() {
        VerdictDto verdict = registry.isExcluded(settings.id())
                ? registry.lastVerdict(settings.id(), "EXCLUDED").orElse(null) : null;
        if (verdict == null) {
            long now = System.currentTimeMillis();
            if (now - lastSelfReport < 3000) {
                return;
            }
            lastSelfReport = now;
            raft.updateOutReason("체인 손상 — 리더 층에 신고해 판정을 기다리는 중");
            ReportAckDto ack = peers.sendToLeaderLayer(NodeReportApi.REPORTS,
                    new DivergenceReportDto(settings.id(), null, null, settings.id(), settings.url()),
                    ReportAckDto.class, ReportAckDto::accepted).orElse(null);
            if (ack == null) {
                return;
            }
            verdict = ack.verdict() != null ? ack.verdict()
                    : new VerdictDto(settings.id(), ack.reason(), ledger.head().index() + 1, null, null, List.of(), "NONE", null, null);
        }
        if ("HALTED".equals(verdict.action())) {
            raft.updateOutReason("기록 중단 — 관리자 확인 필요");
            return;
        }
        long k = Math.max(1, verdict.blockIndex() == null ? 1 : verdict.blockIndex());
        raft.updateOutReason("복구 중: 블록 #" + k + "부터 지우고 다시 받는 중");
        recovering = true;
        try {
            if (ledger.head().index() >= k) {
                log.warn("팀원 복구: 블록 #{}부터 지운다 (판정: {} · {})", k, verdict.reason(), verdict.differentFields());
                ledger.truncateFrom(k);
            }
            if (!ledger.audit(this::signedByLeaderLayer)) {
                log.warn("팀원 복구: #{} 앞 블록도 맞지 않아 처음부터 다시 받는다", k);
                ledger.truncateFrom(1);
                ledger.audit();
            }
            while (pullAny()) {
                // 끝까지 받는다
            }
            Ledger.Head head = ledger.head();
            RestoreResultDto r = peers.sendToLeaderLayer(NodeConsensusApi.RESTORE,
                    new RestoreRequestDto(settings.id(), head.index(), head.hash()),
                    RestoreResultDto.class, RestoreResultDto::accepted).orElse(null);
            if (r != null && r.accepted()) {
                log.info("팀원 복귀 승인: {} (높이 {})", r.message(), head.index());
                audited = true;
                raft.rejoin(r.logIndex() == null ? 0 : r.logIndex());
                keysFetchedAt = 0;      // 제외 명단을 바로 다시 받는다
            }
        } finally {
            recovering = false;
        }
    }

    /** 리더 층 노드 중 한 곳에서라도 블록을 받았으면 true */
    private boolean pullAny() {
        for (String source : sources()) {
            Boolean r = pullFrom(source);
            if (r != null) {
                return r;
            }
        }
        return false;
    }

    private boolean keysKnown() {
        return registry.all().stream().allMatch(m -> m.publicKey() != null);
    }

    /**
     * 공개키 · 제외 명단 · 판정 기록을 리더 층 노드에서 받아 온다 (팀 리더 먼저) — 블록과 같은 길 (미정사항 9번).
     * 제외 여부를 알아야 하므로 1초에 한 번 받는다.
     */
    private void refreshKeys(boolean force) {
        long now = System.currentTimeMillis();
        if (now - keysFetchedAt < 1000) {
            return;
        }
        keysFetchedAt = now;
        for (String leader : sources()) {
            String url = registry.urlOf(leader).orElse(null);
            if (url == null) {
                continue;       // 아직 리더 층에 가입하지 않은 팀 리더 — 주소를 모른다
            }
            try {
                NodeStatusDto s = http.get().uri(url + NodeAdminApi.STATUS)
                        .header(NodeHeaders.NODE_SECRET, settings.secret()).retrieve().body(NodeStatusDto.class);
                if (s == null || s.members() == null) {
                    continue;
                }
                for (MemberInfoDto m : s.members()) {
                    if (m.publicKey() != null) {
                        String known = keyOf(m.id());
                        if (known != null && !known.equals(m.publicKey())) {
                            log.warn("리더 층 노드 {}의 공개키가 바뀌었다고 받음 — 무시한다", m.id());
                            continue;
                        }
                        registry.register(new MemberRecord(m.id(), m.team(), m.url(), m.publicKey()));
                    }
                }
                if (s.excluded() != null) {
                    registry.replaceExcluded(s.excluded());
                }
                if (s.verdicts() != null) {
                    registry.replaceVerdicts(s.verdicts());
                }
                return;
            } catch (Exception e) {
                log.debug("{}에서 공개키 명단을 받지 못함: {}", leader, e.toString());
            }
        }
    }

    /** 팀 리더에게 등록 겸 상태 알림. 팀 리더가 응답하면 true */
    private boolean hello() {
        String url = registry.urlOf(settings.teamLeader()).orElse(null);
        if (url == null) {
            return false;       // 팀 리더가 아직 리더 층에 가입하지 않았다
        }
        Ledger.Head head = ledger.head();
        try {
            http.post().uri(url + NodeTeamApi.MEMBERS)
                    .header(NodeHeaders.NODE_SECRET, settings.secret())
                    .body(new TeamHelloDto(settings.id(), settings.team(), settings.url(), head.index(), head.hash(),
                            raft.outReason() != null ? ReasonCode.CHAIN_INVALID : ReasonCode.OK))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 빠진 블록 받기: 팀 리더 먼저, 팀 리더가 꺼져 있거나 블록을 내주지 않으면 다른 팀 리더 */
    private void pull(boolean leaderUp) {
        for (String source : sources()) {
            Boolean result = pullFrom(source);
            if (result == null) {
                continue;               // 응답 없음 · 거부 → 다음 노드
            }
            if (!Objects.equals(source, lastSource)) {
                if (!source.equals(settings.teamLeader())) {
                    log.info("팀 리더 {}{} — {}에게서 블록을 받는다", settings.teamLeader(),
                            leaderUp ? "가 블록을 내주지 않음" : "가 꺼져 있음", source);
                } else if (lastSource != null) {
                    log.info("팀 리더 {}에게서 다시 블록을 받는다", source);
                }
                lastSource = source;
            }
            return;
        }
    }

    /** @return true: 받음 · false: 받을 것 없음 · null: 이 노드에서는 받지 못함 */
    private Boolean pullFrom(String source) {
        String url = registry.urlOf(source).orElse(null);
        if (url == null) {
            return null;
        }
        try {
            BlockProposalDto[] page = http.get()
                    .uri(url + NodeConsensusApi.BLOCKS + "?from=" + (ledger.head().index() + 1) + "&limit=" + PAGE)
                    .header(NodeHeaders.NODE_SECRET, settings.secret())
                    .retrieve().body(BlockProposalDto[].class);
            if (page == null || page.length == 0) {
                return false;
            }
            int added = 0;
            for (BlockProposalDto b : page) {
                if (!source.equals(b.signerId())) {
                    return null;        // 리더 층 노드는 자기 사본(자기 서명)만 내준다
                }
                ReasonCode r = store(b);
                if (r != ReasonCode.OK) {
                    log.warn("{}에게서 받은 블록 #{} 저장 안 함: {}", source, b.blockIndex(), r);
                    return added > 0 ? Boolean.TRUE : null;
                }
                added++;
            }
            return true;
        } catch (Exception e) {
            return null;
        }
    }

    /** 팀 리더를 먼저, 그다음 다른 리더 층 노드 (제외된 노드는 빼고) */
    private List<String> sources() {
        List<String> ids = new ArrayList<>();
        ids.add(settings.teamLeader());
        for (MemberRecord m : registry.all()) {
            if (!ids.contains(m.id()) && !registry.isExcluded(m.id())) {
                ids.add(m.id());
            }
        }
        return ids;
    }
}
