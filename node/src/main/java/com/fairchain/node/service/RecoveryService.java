package com.fairchain.node.service;

import com.fairchain.node.config.NodeSettings;
import com.fairchain.node.ledger.Ledger;
import com.fairchain.node.raft.MemberRecord;
import com.fairchain.node.raft.MemberRegistry;
import com.fairchain.node.raft.NodeLeftEvent;
import com.fairchain.node.raft.RaftNodeHolder;
import com.fairchain.node.security.NodeKeys;
import com.fairchain.nodeapi.NodeConsensusApi;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.NodeReportApi;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.DivergenceReportDto;
import com.fairchain.nodeapi.dto.ReportAckDto;
import com.fairchain.nodeapi.dto.RestoreRequestDto;
import com.fairchain.nodeapi.dto.RestoreResultDto;
import com.fairchain.nodeapi.dto.VerdictDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 리더 층 노드의 복구 (미정사항 10 · 11번, 계획 4.8).
 *
 * 빠진 노드는 스스로 다음을 한다:
 * <ol>
 *   <li>판정이 없으면(스스로 체인 손상을 발견) 리더에게 자기 자신을 신고해 판정(#k)을 받는다</li>
 *   <li>H2에서 #k부터 지운다. Raft 상태(임기 · 투표 · 로그)는 건드리지 않는다</li>
 *   <li>정상 리더 층 노드에서 #k부터 다시 받는다 — 보낸 노드의 서명과 해시를 확인하고 내 키로 서명해 쓴다</li>
 *   <li>리더에게 복귀를 요청한다. 리더가 확정 체인과 같은지 보고 Raft 로그에 복귀를 기록하면 Raft에 다시 참여한다</li>
 * </ol>
 * 1번 블록부터 다른 경우(장부 전체 손상)는 표준대로 관리자가 해지 → 비우기 → learner로 재가입한다 (미정사항 11번).
 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);
    private static final int PAGE = 200;
    private static final long RETRY_MILLIS = 1500;

    private final Ledger ledger;
    private final RaftNodeHolder raft;
    private final MemberRegistry registry;
    private final NodeSettings settings;
    private final NodeKeys keys;
    private final PeerClient peers;
    private final RestClient http;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "recovery");
        t.setDaemon(true);
        return t;
    });

    public RecoveryService(Ledger ledger, RaftNodeHolder raft, MemberRegistry registry, NodeSettings settings,
                           NodeKeys keys, PeerClient peers) {
        this.ledger = ledger;
        this.raft = raft;
        this.registry = registry;
        this.settings = settings;
        this.keys = keys;
        this.peers = peers;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1500);
        factory.setReadTimeout(3000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @PreDestroy
    public void stop() {
        worker.shutdownNow();
    }

    @EventListener
    public void onLeft(NodeLeftEvent event) {
        if (settings.leaderLayer()) {
            try {
                worker.execute(() -> recover(event.verdict()));
            } catch (RuntimeException ignored) {
                // 끄는 중
            }
        }
    }

    private void recover(VerdictDto verdict) {
        boolean truncated = false;
        // 해지된 노드는 복구하지 않는다 — 돌아올 자리가 없다 (다시 넣으려면 새 ID로 가입)
        while (!Thread.currentThread().isInterrupted() && raft.outReason() != null && !raft.removedFromGroup()) {
            try {
                if (verdict == null || verdict.blockIndex() == null) {
                    raft.updateOutReason("체인 손상 — 리더에게 스스로 신고해 판정을 기다리는 중");
                    ReportAckDto ack = peers.sendToLeaderLayer(NodeReportApi.REPORTS,
                            new DivergenceReportDto(settings.id(), null, null, settings.id(), settings.url()),
                            ReportAckDto.class, ReportAckDto::accepted).orElse(null);
                    if (ack == null) {
                        sleep();
                        continue;
                    }
                    verdict = ack.verdict() != null ? ack.verdict()
                            // 판정에서 차이가 없었다 (예: 잠깐 깨졌다 돌아옴) — 지울 것 없이 확인만 하고 복귀
                            : new VerdictDto(settings.id(), ack.reason(), ledger.head().index() + 1, null, null, List.of(),
                            "NONE", null, null);
                }
                if ("HALTED".equals(verdict.action())) {
                    raft.updateOutReason("기록 중단 — 서명이 맞는 서로 다른 블록 #" + verdict.blockIndex() + " (관리자 확인 필요)");
                    return;
                }
                long k = verdict.blockIndex();
                if (k <= 1) {
                    raft.updateOutReason("장부 전체가 확정 체인과 다름(#1부터) — 관리자: 해지 → 비우기 → learner로 재가입 (미정사항 11번)");
                    log.error("{}", raft.outReason());
                    return;
                }
                if (!truncated) {
                    raft.updateOutReason("복구 중: 블록 #" + k + "부터 지우고 다시 받는 중");
                    log.warn("복구: 블록 #{}부터 지운다 (판정: {} · {})", k, verdict.reason(), verdict.differentFields());
                    ledger.truncateFrom(k);
                    truncated = true;
                    if (!ledger.audit(keys::signedByMe)) {
                        raft.updateOutReason("복구 실패: #" + k + " 앞 블록도 맞지 않음 — 관리자 확인 필요");
                        return;
                    }
                }
                pullFromPeers();
                Ledger.Head head = ledger.head();
                raft.updateOutReason("복구 중: 블록 " + head.index() + "번까지 다시 받음 — 리더에게 복귀 요청");
                RestoreResultDto r = peers.sendToLeaderLayer(NodeConsensusApi.RESTORE,
                        new RestoreRequestDto(settings.id(), head.index(), head.hash()),
                        RestoreResultDto.class, RestoreResultDto::accepted).orElse(null);
                if (r != null && r.accepted()) {
                    log.info("복귀 승인: {} (로그 #{})", r.message(), r.logIndex());
                    raft.rejoin(r.logIndex() == null ? 0 : r.logIndex());
                    return;
                }
                sleep();
            } catch (RuntimeException e) {
                log.warn("복구 중 오류: {}", e.toString());
                sleep();
            }
        }
    }

    /**
     * 켤 때 검사에서 바로 빠져 Raft를 한 번도 돌리지 않은 노드는 다른 노드의 공개키를 모른다 (명단은 Raft 로그로 받으므로).
     * 정상 리더 층 노드의 상태 조회에서 공개키를 받아 둔다 — 팀원이 키를 받는 길과 같다. 이미 아는 키는 바꾸지 않는다.
     */
    private void ensurePeerKeys() {
        if (registry.all().stream().allMatch(m -> m.publicKey() != null)) {
            return;
        }
        for (MemberRecord m : registry.all()) {
            if (m.id().equals(settings.id()) || m.url() == null) {
                continue;
            }
            try {
                var s = http.get().uri(m.url() + com.fairchain.nodeapi.NodeAdminApi.STATUS)
                        .header(NodeHeaders.NODE_SECRET, settings.secret())
                        .retrieve().body(com.fairchain.nodeapi.dto.NodeStatusDto.class);
                if (s == null || s.members() == null) {
                    continue;
                }
                for (var info : s.members()) {
                    boolean unknown = registry.get(info.id()).map(MemberRecord::publicKey).orElse(null) == null;
                    if (info.publicKey() != null && unknown && !info.id().equals(settings.id())) {
                        registry.register(new MemberRecord(info.id(), info.team(), info.url(), info.publicKey()));
                    }
                }
                return;
            } catch (Exception e) {
                log.debug("{}에서 공개키 명단을 받지 못함: {}", m.id(), e.toString());
            }
        }
    }

    /** 정상 리더 층 노드에서 빠진 블록을 끝까지 받는다 (보낸 노드 서명 확인 → 내 서명으로 저장) */
    private void pullFromPeers() {
        ensurePeerKeys();
        for (MemberRecord m : registry.all()) {
            if (m.id().equals(settings.id()) || registry.isExcluded(m.id()) || m.url() == null) {
                continue;
            }
            int added = 0;
            boolean ok = true;
            while (ok) {
                BlockProposalDto[] page;
                try {
                    page = http.get().uri(m.url() + NodeConsensusApi.BLOCKS + "?from=" + (ledger.head().index() + 1)
                                    + "&limit=" + PAGE)
                            .header(NodeHeaders.NODE_SECRET, settings.secret()).retrieve().body(BlockProposalDto[].class);
                } catch (Exception e) {
                    break;
                }
                if (page == null || page.length == 0) {
                    break;
                }
                for (BlockProposalDto b : page) {
                    if (!m.id().equals(b.signerId()) || m.publicKey() == null || !NodeKeys.verify(m.publicKey(), b)) {
                        log.warn("복구: {}의 블록 #{} 서명이 맞지 않음 — 다른 노드에서 받는다", m.id(), b.blockIndex());
                        ok = false;
                        break;
                    }
                    Ledger.Append r = ledger.append(keys.sign(b));
                    if (r != Ledger.Append.APPENDED && r != Ledger.Append.ALREADY_HAVE) {
                        log.warn("복구: {}의 블록 #{}를 붙이지 못함: {}", m.id(), b.blockIndex(), r);
                        ok = false;
                        break;
                    }
                    added++;
                }
            }
            if (added > 0) {
                log.info("복구: {}에서 블록 {}개를 다시 받음 (높이 {})", m.id(), added, ledger.head().index());
            }
            if (ok) {
                return;     // 이 노드에서 끝까지 받았다
            }
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
