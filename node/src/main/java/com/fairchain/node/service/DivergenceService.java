package com.fairchain.node.service;

import com.fairchain.node.config.NodeSettings;
import com.fairchain.node.entity.Block;
import com.fairchain.node.ledger.Ledger;
import com.fairchain.node.raft.LedgerOp;
import com.fairchain.node.raft.MemberRecord;
import com.fairchain.node.raft.MemberRegistry;
import com.fairchain.node.raft.RaftNodeHolder;
import com.fairchain.node.security.NodeKeys;
import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.nodeapi.NodeConsensusApi;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.NodeReportApi;
import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.BlockHashDto;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.DivergenceReportDto;
import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.nodeapi.dto.ReasonCode;
import com.fairchain.nodeapi.dto.ReportAckDto;
import com.fairchain.nodeapi.dto.RestoreRequestDto;
import com.fairchain.nodeapi.dto.RestoreResultDto;
import com.fairchain.nodeapi.dto.VerdictDto;
import io.microraft.Ordered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 불일치 신고 · 판정 · 복귀 (미정사항 13 · 14 · 11번, 이고르 「노드 간 불일치 — 누가 틀렸는지 판별」).
 *
 * 신고는 리더 층 리더가 받는다 (다른 노드는 리더에게 넘긴다). 신고하는 쪽이 FairChain이든 팀 리더든 노드 자신이든 같은 API다.
 * 판정 순서:
 * <ol>
 *   <li>뒤처짐(BEHIND)인가 갈라짐(DIVERGED)인가 — 마지막 번호 · 해시 비교. 뒤처짐이면 아무 조치 없음 (Raft · 따라잡기가 메운다)</li>
 *   <li>처음 갈라진 블록 #k 찾기 — 해시는 앞 블록 해시를 품으므로 이진 탐색 (블록 1만 개면 약 14번 요청).
 *       대상 노드의 체인이 스스로도 깨져 있으면(해시 재계산 실패) 해시가 단조롭지 않아 앞에서부터 차례로 비교한다</li>
 *   <li>#k의 서명 검증 — 대상 노드의 #k가 해시 · 서명이 맞지 않으면 그 노드가 틀렸다 (다수결이 아니라 증거)</li>
 *   <li>결과와 증거(#k의 두 버전 · 달라진 필드 · 시각)를 Raft 로그에 "제외"로 기록 (CometBFT evidence와 같은 모양)</li>
 * </ol>
 * #k의 두 버전이 모두 서명이 맞는데 내용이 다르면 키 유출 · 포크로 보고 기록을 멈춘다 — 관리자 확인 (11/06 변경안 6장).
 * FairChain은 신고만 한다. 판정은 리더 층만 한다.
 */
@Service
public class DivergenceService {

    private static final Logger log = LoggerFactory.getLogger(DivergenceService.class);
    private static final int PAGE = 200;
    /** 다른 노드를 판정하기 전 내 체인을 서명까지 처음부터 다시 검사하는 간격 (그 사이에는 새로 붙은 블록만) */
    private static final long SELF_AUDIT_FULL_EVERY_MILLIS = 60_000;

    private final Ledger ledger;
    private final RaftNodeHolder raft;
    private final MemberRegistry registry;
    private final NodeSettings settings;
    private final NodeKeys keys;
    private final PeerClient peers;
    private final RestClient http;
    private final Object judgeLock = new Object();

    public DivergenceService(Ledger ledger, RaftNodeHolder raft, MemberRegistry registry, NodeSettings settings,
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

    // ------------------------------------------------------------------ 신고 받기

    /** @param forwarded 다른 노드가 넘겨준 신고면 true — 다시 넘기지 않는다 */
    public ReportAckDto report(DivergenceReportDto report, boolean forwarded) {
        if (report == null || report.suspectId() == null || report.suspectId().isBlank()) {
            return new ReportAckDto(false, ReasonCode.INVALID_BLOCK);
        }
        if (!raft.isLeader()) {
            if (forwarded) {
                return new ReportAckDto(false, ReasonCode.NOT_LEADER);
            }
            return toLeader(NodeReportApi.REPORTS, report, ReportAckDto.class, ReportAckDto::accepted)
                    .orElse(new ReportAckDto(false, ReasonCode.NOT_LEADER));
        }
        synchronized (judgeLock) {
            try {
                return judge(report);
            } catch (Exception e) {
                log.warn("{}에 대한 신고를 판정하지 못함: {}", report.suspectId(), e.toString());
                return new ReportAckDto(false, null);
            }
        }
    }

    /** 리더가 아니면 리더에게(모르면 다른 리더 층 노드에게 차례로) 넘긴다 */
    private <T> Optional<T> toLeader(String path, Object body, Class<T> type, java.util.function.Predicate<T> ok) {
        Optional<String> leader = raft.leaderId().filter(id -> !id.equals(settings.id()));
        if (leader.isPresent()) {
            Optional<T> r = peers.forward(leader.get(), path, body, type);
            if (r.isPresent() && ok.test(r.get())) {
                return r;
            }
        }
        for (MemberRecord m : registry.all()) {
            if (!m.id().equals(settings.id()) && !registry.isExcluded(m.id())) {
                Optional<T> r = peers.forward(m.id(), path, body, type);
                if (r.isPresent() && ok.test(r.get())) {
                    return r;
                }
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ 판정 (리더)

    private ReportAckDto judge(DivergenceReportDto report) throws Exception {
        String suspect = report.suspectId();
        if (suspect.equals(settings.id())) {
            // 리더 자신에 대한 신고: 내 체인을 서명까지 검사. 틀렸으면 내가 빠지고, 새 리더가 다시 판정한다 (미정사항 14번)
            if (!ledger.audit(keys::signedByMe)) {
                raft.chainInvalid("신고를 받고 내 체인을 서명까지 검사함: 맞지 않음");
                return new ReportAckDto(true, ReasonCode.CHAIN_INVALID);
            }
            return new ReportAckDto(true, ReasonCode.OK);
        }
        if (registry.isExcluded(suspect)) {
            return new ReportAckDto(true, ReasonCode.DIVERGED, registry.lastVerdict(suspect, "EXCLUDED").orElse(null));
        }
        String url = registry.urlOf(suspect).orElse(report.suspectUrl());
        if (url == null || url.isBlank()) {
            return new ReportAckDto(false, null);
        }
        // 판정하는 쪽의 체인부터 맞아야 한다 — 서명까지 검사. 전체는 1분에 한 번, 그 사이에는 새로 붙은 블록만 (Ledger.auditSigned)
        if (!ledger.auditSigned(keys::signedByMe, SELF_AUDIT_FULL_EVERY_MILLIS)) {
            raft.chainInvalid("판정 전 내 체인 검사 실패");
            return new ReportAckDto(false, ReasonCode.CHAIN_INVALID);
        }

        NodeLedgerStatusDto status = get(url + NodeAuditApi.LEDGER_STATUS, NodeLedgerStatusDto.class);
        if (status == null) {
            return new ReportAckDto(false, null);
        }
        long theirs = status.height() == null ? 0 : status.height();
        boolean selfBroken = !status.chainValid();
        Ledger.Head mine = ledger.head();

        Long k;
        int requests = 0;
        if (!selfBroken) {
            long m = Math.min(theirs, mine.index());
            if (m > 0 && !Objects.equals(hashAt(url, m), myHash(m))) {
                long lo = 1;
                long hi = m;            // hi에서는 다르다
                while (lo < hi) {
                    long mid = (lo + hi) / 2;
                    requests++;
                    if (Objects.equals(hashAt(url, mid), myHash(mid))) {
                        lo = mid + 1;
                    } else {
                        hi = mid;
                    }
                }
                k = lo;
                log.info("{}의 처음 갈라진 블록 #{} — 해시 이진 탐색 {}번", suspect, k, requests + 1);
            } else if (theirs > mine.index()) {
                k = mine.index() + 1;   // 확정 체인에 없는 블록을 갖고 있다
            } else {
                k = null;
            }
        } else {
            k = firstDifference(url, theirs);
            log.info("{}의 체인이 스스로도 깨져 있음 — 앞에서부터 비교해 처음 다른 블록 #{}", suspect, k);
        }
        if (k == null) {
            ReasonCode r = theirs < mine.index() ? ReasonCode.BEHIND : ReasonCode.OK;
            log.info("{} 신고 판정: {} (그 노드 {}번 · 확정 {}번)", suspect, r, theirs, mine.index());
            return new ReportAckDto(true, r);
        }

        BlockProposalDto found = suspectBlock(url, k);
        BlockProposalDto expected = myBlock(k);
        if (expected == null && found != null) {
            Thread.sleep(500);      // 확정이 막 늘어나는 중일 수 있다
            expected = myBlock(k);
        }
        boolean signatureOk = found != null && signedByLeaderLayer(found);
        boolean foundValid = found != null && ledger.wellFormed(found) && signatureOk;
        List<String> fields = differentFields(expected, found, signatureOk);
        long now = System.currentTimeMillis();

        if (foundValid && (expected == null || !expected.blockHash().equals(found.blockHash()))) {
            // 서명이 맞는 서로 다른 두 블록 — 키를 가진 노드가 확정되지 않은 블록에 서명했다. 자동으로 판정하지 않는다
            VerdictDto verdict = new VerdictDto(suspect, ReasonCode.DIVERGED, k, expected, found, fields, "HALTED",
                    settings.id(), now);
            raft.replicate(new LedgerOp.Halt(verdict), settings.commitTimeout());
            log.error("{}의 블록 #{}: 서명이 맞는데 확정 블록과 다르다 — 기록을 멈춘다 (관리자 확인)", suspect, k);
            return new ReportAckDto(true, ReasonCode.DIVERGED, verdict);
        }
        ReasonCode reason = selfBroken ? ReasonCode.CHAIN_INVALID : ReasonCode.DIVERGED;
        VerdictDto verdict = new VerdictDto(suspect, reason, k, expected, found, fields, "EXCLUDED", settings.id(), now);
        raft.replicate(new LedgerOp.Exclude(suspect, verdict), settings.commitTimeout());
        log.warn("{} 제외: 블록 #{} ({}), 달라진 필드 {} — 신고: {}", suspect, k, reason, fields, report.reporter());
        return new ReportAckDto(true, reason, verdict);
    }

    /** 앞에서부터 대상 노드의 블록을 확정 체인과 비교해 처음 다른 번호 (내용 · 해시 재계산 · 서명) */
    private Long firstDifference(String url, long theirs) {
        long from = 1;
        while (from <= theirs) {
            BlockDto[] page = get(url + NodeAuditApi.BLOCKS + "?from=" + from + "&limit=" + PAGE, BlockDto[].class);
            if (page == null || page.length == 0) {
                return null;
            }
            for (BlockDto d : page) {
                BlockProposalDto b = proposal(d);
                BlockProposalDto mine = myBlock(b.blockIndex());
                if (mine == null || !sameContent(mine, b) || !ledger.wellFormed(b) || !signedByLeaderLayer(b)) {
                    return b.blockIndex();
                }
                from = b.blockIndex() + 1;
            }
        }
        return null;
    }

    private static boolean sameContent(BlockProposalDto a, BlockProposalDto b) {
        return Objects.equals(a.blockIndex(), b.blockIndex()) && Objects.equals(a.prevBlockHash(), b.prevBlockHash())
                && Objects.equals(a.recordKey(), b.recordKey()) && Objects.equals(a.version(), b.version())
                && Objects.equals(a.recordHash(), b.recordHash()) && Objects.equals(a.blockHash(), b.blockHash());
    }

    private static List<String> differentFields(BlockProposalDto expected, BlockProposalDto found, boolean signatureOk) {
        List<String> fields = new ArrayList<>();
        if (found == null) {
            fields.add("missing");
            return fields;
        }
        if (expected == null) {
            fields.add("notConfirmed");
        } else {
            if (!Objects.equals(expected.prevBlockHash(), found.prevBlockHash())) {
                fields.add("prevBlockHash");
            }
            if (!Objects.equals(expected.recordKey(), found.recordKey())) {
                fields.add("recordKey");
            }
            if (!Objects.equals(expected.version(), found.version())) {
                fields.add("version");
            }
            if (!Objects.equals(expected.recordHash(), found.recordHash())) {
                fields.add("recordHash");
            }
            if (!Objects.equals(expected.blockHash(), found.blockHash())) {
                fields.add("blockHash");
            }
        }
        if (!signatureOk) {
            fields.add("signature");
        }
        return fields;
    }

    /** 리더 층 노드의 등록된 공개키로 서명이 맞는지 */
    private boolean signedByLeaderLayer(BlockProposalDto b) {
        String key = b.signerId() == null ? null : registry.get(b.signerId()).map(MemberRecord::publicKey).orElse(null);
        return key != null && NodeKeys.verify(key, b);
    }

    private String hashAt(String url, long index) {
        BlockHashDto h = get(url + NodeConsensusApi.HASH + "?index=" + index, BlockHashDto.class);
        return h == null ? null : h.blockHash();
    }

    private String myHash(long index) {
        return ledger.block(index).map(b -> b.blockHash).orElse(null);
    }

    private BlockProposalDto myBlock(long index) {
        return ledger.block(index).map(Ledger::toProposal).orElse(null);
    }

    private BlockProposalDto suspectBlock(String url, long index) {
        BlockDto[] page = get(url + NodeAuditApi.BLOCKS + "?from=" + index + "&limit=1", BlockDto[].class);
        if (page == null || page.length == 0 || !Objects.equals(page[0].blockIndex(), index)) {
            return null;
        }
        return proposal(page[0]);
    }

    private static BlockProposalDto proposal(BlockDto d) {
        return new BlockProposalDto(d.blockIndex(), d.prevBlockHash(), d.recordKey(), d.version(), d.recordHash(),
                d.timestamp() == null ? null : d.timestamp().toInstant(ZoneOffset.UTC).toEpochMilli(), d.blockHash(),
                d.signerId(), d.signature());
    }

    private <T> T get(String url, Class<T> type) {
        try {
            return http.get().uri(url).header(NodeHeaders.NODE_SECRET, settings.secret()).retrieve().body(type);
        } catch (Exception e) {
            log.debug("{} 조회 실패: {}", url, e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ 복귀 · 재개 (리더)

    /** 제외된 노드가 다시 받은 체인이 확정 체인과 같으면 Raft 로그에 복귀를 기록한다 */
    public RestoreResultDto restore(RestoreRequestDto req, boolean forwarded) {
        if (req == null || req.nodeId() == null || req.height() == null || req.height() < 0 || req.headHash() == null) {
            return new RestoreResultDto(false, "노드 ID · 높이 · 마지막 해시가 필요하다", null);
        }
        if (!raft.isLeader()) {
            if (forwarded) {
                return new RestoreResultDto(false, "리더가 아니다 (NOT_LEADER)", null);
            }
            return toLeader(NodeConsensusApi.RESTORE, req, RestoreResultDto.class, RestoreResultDto::accepted)
                    .orElse(new RestoreResultDto(false, "리더에게 전달하지 못함", null));
        }
        if (registry.halted()) {
            return new RestoreResultDto(false, "기록 중단 상태 — 관리자 확인 뒤 재개해야 한다", null);
        }
        Ledger.Head mine = ledger.head();
        if (req.height() > mine.index()) {
            return new RestoreResultDto(false, "확정 체인(" + mine.index() + "번)보다 높다 — 다시 시도", null);
        }
        String expected = req.height() == 0 ? Ledger.GENESIS : myHash(req.height());
        if (!req.headHash().equals(expected)) {
            return new RestoreResultDto(false, req.height() + "번 블록 해시가 확정 체인과 다르다", null);
        }
        if (!registry.isExcluded(req.nodeId())) {
            return new RestoreResultDto(true, req.nodeId() + "는 제외 상태가 아니다",
                    registry.restoredAt(req.nodeId()).orElse(0L));
        }
        try {
            Ordered<Object> o = raft.replicateOrdered(new LedgerOp.Restore(req.nodeId(), req.height(), req.headHash(),
                    settings.id(), System.currentTimeMillis()), settings.commitTimeout());
            log.info("{} 복귀 기록 (높이 {}, 로그 #{})", req.nodeId(), req.height(), o.getCommitIndex());
            return new RestoreResultDto(true, req.nodeId() + " 복귀", o.getCommitIndex());
        } catch (Exception e) {
            return new RestoreResultDto(false, "Raft 로그에 기록하지 못함: " + e.getClass().getSimpleName(), null);
        }
    }

    /** 관리자: 기록 재개 */
    public MembershipResultDto resume(boolean forwarded) {
        if (!raft.isLeader()) {
            if (forwarded) {
                return new MembershipResultDto(false, "리더가 아니다 (NOT_LEADER)");
            }
            return toLeader(NodeAdminApi.RESUME, null, MembershipResultDto.class, MembershipResultDto::accepted)
                    .orElse(new MembershipResultDto(false, "리더에게 전달하지 못함"));
        }
        if (!registry.halted()) {
            return new MembershipResultDto(true, "기록이 멈춰 있지 않다");
        }
        try {
            raft.replicate(new LedgerOp.Resume(settings.id(), System.currentTimeMillis()), settings.commitTimeout());
            return new MembershipResultDto(true, "기록 재개");
        } catch (Exception e) {
            return new MembershipResultDto(false, "Raft 로그에 기록하지 못함: " + e.getClass().getSimpleName());
        }
    }

    /** 내 블록 (복구 · 판정 도구) */
    Optional<Block> block(long index) {
        return ledger.block(index);
    }
}
