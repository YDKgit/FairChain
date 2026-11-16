package com.fairchain.node.team;

import com.fairchain.node.config.NodeSettings;
import com.fairchain.node.ledger.Ledger;
import com.fairchain.node.raft.BlockStoredEvent;
import com.fairchain.node.raft.MemberRegistry;
import com.fairchain.node.raft.RaftNodeHolder;
import com.fairchain.node.service.DivergenceService;
import com.fairchain.nodeapi.dto.DivergenceReportDto;
import com.fairchain.nodeapi.dto.ReportAckDto;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.NodeMemberApi;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.MemberAckDto;
import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.nodeapi.dto.ReasonCode;
import com.fairchain.nodeapi.dto.TeamHelloDto;
import com.fairchain.nodeapi.dto.TeamMemberDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 팀 리더(리더 층 노드) 쪽 팀원 관리 (11/12 답변 2번, 계획 4.9).
 *
 * <ul>
 *   <li>팀원이 1초마다 보내는 등록 · 상태 알림으로 팀원 명단(주소 · 마지막 번호 · 해시)을 안다. 명단은 Raft 로그에 넣지 않는다 —
 *       팀원 추가 · 해지는 리더 층 설정을 건드리지 않는다. 팀 리더를 다시 켜도 팀원이 1초 안에 다시 등록한다</li>
 *   <li>확정 블록을 내 H2에 쓰면 팀원에게 내 사본(내 서명)을 보낸다. 따로 도는 스레드라 확정 · FairChain 응답을 늦추지 않는다</li>
 *   <li>팀원의 응답(저장함 · 거부 이유 · 마지막 번호와 해시)으로 어느 팀원이 다른지 안다. 판정 · 제외 기록은 5단계</li>
 * </ul>
 */
@Service
public class TeamLeaderService {

    private static final Logger log = LoggerFactory.getLogger(TeamLeaderService.class);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");
    private static final int PUSH_PAGE = 100;

    private final Ledger ledger;
    private final RaftNodeHolder raft;
    private final NodeSettings settings;
    private final MemberRegistry registry;
    private final DivergenceService divergence;
    private final RestClient http;
    private final Map<String, TeamMember> members = new ConcurrentHashMap<>();
    private final ExecutorService pusher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "team-push");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService reporter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "team-report");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean queued = new AtomicBoolean();

    /** 팀원 하나 — 팀원의 알림 · 응답으로 갱신한다 */
    private static final class TeamMember {
        final String id;
        volatile String url;
        volatile long lastIndex;
        volatile String lastHash;
        volatile ReasonCode state = ReasonCode.OK;
        volatile long lastSeen;
        /** 내 블록을 거부했을 때 팀원의 마지막 해시 */
        volatile String rejectedAt;
        volatile long reportedAt;

        TeamMember(String id) {
            this.id = id;
        }
    }

    public TeamLeaderService(Ledger ledger, RaftNodeHolder raft, NodeSettings settings, MemberRegistry registry,
                             DivergenceService divergence) {
        this.ledger = ledger;
        this.raft = raft;
        this.settings = settings;
        this.registry = registry;
        this.divergence = divergence;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(2000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @PreDestroy
    public void stop() {
        pusher.shutdownNow();
        reporter.shutdownNow();
    }

    // ------------------------------------------------------------------ 팀원 → 팀 리더

    public MembershipResultDto hello(TeamHelloDto h) {
        if (!settings.leaderLayer()) {
            return new MembershipResultDto(false, settings.id() + "는 팀 리더가 아니다 (팀원 노드)");
        }
        if (h == null || h.id() == null || !ID.matcher(h.id()).matches() || h.url() == null || h.url().isBlank()) {
            return new MembershipResultDto(false, "팀원 ID와 주소가 필요하다");
        }
        if (!settings.team().equals(h.team())) {
            return new MembershipResultDto(false, h.id() + "는 팀 " + h.team() + " 소속 — 이 노드는 팀 " + settings.team() + "의 팀 리더");
        }
        if (h.id().equals(settings.id())) {
            return new MembershipResultDto(false, "팀 리더 자신은 팀원이 될 수 없다");
        }
        TeamMember m = members.computeIfAbsent(h.id(), id -> {
            log.info("팀원 {} 등록 ({})", id, h.url());
            return new TeamMember(id);
        });
        m.url = h.url().replaceAll("/+$", "");
        m.lastIndex = h.lastBlockIndex() == null ? 0 : h.lastBlockIndex();
        m.lastHash = h.lastBlockHash();
        m.lastSeen = System.currentTimeMillis();
        setState(m, judge(m, h.state()));
        trigger();
        return new MembershipResultDto(true, "팀 " + settings.team() + "의 팀원으로 등록됨");
    }

    /**
     * 팀원이 알려 준 마지막 블록이 내 확정 블록과 같은지 본다 (기준은 리더 층에서 확정된 블록 — 11/06 변경안 3-2).
     * 해시는 앞 블록 해시를 품고 있으므로 마지막 해시가 같으면 그 앞 체인도 같다.
     */
    private ReasonCode judge(TeamMember m, ReasonCode reported) {
        if (reported == ReasonCode.CHAIN_INVALID) {
            return ReasonCode.CHAIN_INVALID;
        }
        Ledger.Head head = ledger.head();
        if (m.lastIndex > 0 && m.lastIndex <= head.index()) {
            String mine = ledger.block(m.lastIndex).map(b -> b.blockHash).orElse(null);
            if (mine != null && !mine.equals(m.lastHash)) {
                return ReasonCode.DIVERGED;
            }
        }
        if (m.state == ReasonCode.INVALID_BLOCK && java.util.Objects.equals(m.lastHash, m.rejectedAt)) {
            return m.state;     // 내 블록을 거부한 뒤 그대로인 팀원 — 다시 보내 봐야 같은 거부라서 그대로 둔다
        }
        return m.lastIndex < head.index() ? ReasonCode.BEHIND : ReasonCode.OK;
    }

    private void setState(TeamMember m, ReasonCode state) {
        ReasonCode before = m.state;
        m.state = state;
        if (state == ReasonCode.OK || state == ReasonCode.BEHIND) {
            return;
        }
        if (before != state) {
            log.warn("팀원 {}가 확정 블록과 다르다: {} (마지막 #{})", m.id, state, m.lastIndex);
        }
        reportIfNeeded(m);
    }

    /**
     * 팀원의 불일치를 리더 층 리더에게 신고한다 — 판정과 제외는 리더가 하고 Raft 로그에 남긴다 (11/12 답변 10번).
     * 이미 제외됐으면 다시 신고하지 않는다. 신고가 닿지 않았으면(리더 선출 중 등) 5초 뒤 다시.
     */
    private void reportIfNeeded(TeamMember m) {
        long now = System.currentTimeMillis();
        if (registry.isExcluded(m.id) || now - m.reportedAt < 5000) {
            return;
        }
        m.reportedAt = now;
        try {
            reporter.execute(() -> {
                ReportAckDto ack = divergence.report(
                        new DivergenceReportDto(m.id, m.lastIndex, null, settings.id(), m.url), false);
                log.info("팀원 {} 신고 → 판정 {}{}", m.id, ack.reason(),
                        ack.verdict() == null ? "" : " (블록 #" + ack.verdict().blockIndex() + " " + ack.verdict().action() + ")");
                if (!ack.accepted()) {
                    m.reportedAt = 0;
                }
            });
        } catch (RuntimeException ignored) {
            // 끄는 중
        }
    }

    // ------------------------------------------------------------------ 팀 리더 → 팀원: 블록 보내기

    @EventListener
    public void onBlockStored(BlockStoredEvent event) {
        trigger();
    }

    private void trigger() {
        if (!members.isEmpty() && queued.compareAndSet(false, true)) {
            try {
                pusher.execute(this::pushAll);
            } catch (RuntimeException e) {
                queued.set(false);
            }
        }
    }

    /** 뒤처진 팀원마다 빠진 블록을 내 사본에서 보낸다. 체인이 다른 팀원에게는 보내지 않는다 (5단계에서 복구) */
    private void pushAll() {
        queued.set(false);
        if (raft.outReason() != null) {
            return;     // 빠진 노드는 블록을 나눠 주지 않는다 (11/12 답변 10번)
        }
        boolean progressed = true;
        while (progressed && !Thread.currentThread().isInterrupted()) {
            progressed = false;
            long head = ledger.head().index();
            for (TeamMember m : members.values()) {
                if (m.lastIndex >= head || (m.state != ReasonCode.OK && m.state != ReasonCode.BEHIND)
                        || registry.isExcluded(m.id)) {
                    continue;
                }
                List<BlockProposalDto> blocks = ledger.blocks(m.lastIndex + 1, PUSH_PAGE);
                if (blocks.isEmpty()) {
                    continue;
                }
                try {
                    MemberAckDto ack = http.post().uri(m.url + NodeMemberApi.BLOCKS)
                            .header(NodeHeaders.NODE_SECRET, settings.secret())
                            .body(blocks)
                            .retrieve().body(MemberAckDto.class);
                    if (ack == null) {
                        continue;
                    }
                    long before = m.lastIndex;
                    m.lastIndex = ack.lastBlockIndex() == null ? 0 : ack.lastBlockIndex();
                    m.lastHash = ack.lastBlockHash();
                    m.lastSeen = System.currentTimeMillis();
                    if (ack.stored()) {
                        setState(m, m.lastIndex < ledger.head().index() ? ReasonCode.BEHIND : ReasonCode.OK);
                    } else if (ack.reason() == ReasonCode.BEHIND) {
                        setState(m, ReasonCode.BEHIND);     // 팀원이 공개키를 아직 모르거나 앞 블록이 없다 — 다음에 다시
                    } else {
                        m.rejectedAt = m.lastHash;
                        setState(m, ack.reason() == null ? ReasonCode.INVALID_BLOCK : ack.reason());
                    }
                    progressed |= m.lastIndex > before;
                } catch (Exception e) {
                    log.debug("팀원 {}에게 블록 보내기 실패: {}", m.id, e.toString());
                }
            }
        }
    }

    // ------------------------------------------------------------------ 상태

    public List<TeamMemberDto> members() {
        return members.values().stream()
                .sorted(Comparator.comparing(m -> m.id))
                .map(m -> new TeamMemberDto(m.id, m.url, m.lastIndex, m.lastHash, m.state, m.lastSeen))
                .toList();
    }

    public Optional<ReasonCode> stateOf(String memberId) {
        return Optional.ofNullable(members.get(memberId)).map(m -> m.state);
    }
}
