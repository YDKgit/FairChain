package com.fairchain.node.service;

import com.fairchain.node.config.NodeSettings;
import com.fairchain.node.entity.Block;
import com.fairchain.node.ledger.Ledger;
import com.fairchain.node.raft.ApplyResult;
import com.fairchain.node.raft.LedgerOp;
import com.fairchain.node.raft.MemberRecord;
import com.fairchain.node.raft.MemberRegistry;
import com.fairchain.node.raft.RaftNodeHolder;
import com.fairchain.nodeapi.BlockRecordApi;
import com.fairchain.nodeapi.dto.BlockBatchVerifyRequestDto;
import com.fairchain.nodeapi.dto.BlockBatchVerifyResponseDto;
import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.BlockHashDto;
import com.fairchain.nodeapi.dto.BlockProofDto;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyItemResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import com.fairchain.nodeapi.dto.MemberInfoDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import com.fairchain.nodeapi.dto.ReasonCode;
import io.microraft.RaftEndpoint;
import io.microraft.exception.CannotReplicateException;
import io.microraft.exception.IndeterminateStateException;
import io.microraft.exception.NotLeaderException;
import io.microraft.report.RaftGroupMembers;
import io.microraft.report.RaftTerm;
import com.fairchain.node.team.TeamLeaderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 블록 기록 · 검증.
 *
 * 기록: 아무 리더 층 노드에나 와도 된다. 팔로워는 Raft 리더에게 한 번 넘기고(Fabric 방식, 미정사항 5번), 리더는
 * 블록을 만들어 Raft 로그로 복제한다. 과반이 저장하면 확정이고, 리더에서 적용된 결과를 FairChain에 돌려준다 (왕복 1번).
 * 같은 기록(recordKey + version)이 다시 오면 기존 블록 번호를 돌려준다 (미정사항 6번).
 *
 * 검증 · 조회는 지금처럼 이 노드의 H2만 읽는다.
 */
@Service
public class NodeService {

    private static final Logger log = LoggerFactory.getLogger(NodeService.class);
    private static final int SUBMIT_ATTEMPTS = 2;
    private static final Duration READY_TIMEOUT = Duration.ofMillis(1000);

    private final Ledger ledger;
    private final RaftNodeHolder raft;
    private final MemberRegistry registry;
    private final NodeSettings settings;
    private final PeerClient peers;
    private final TeamLeaderService team;

    /** 리더가 만드는 블록은 한 번에 하나 — 앞 블록이 적용돼야 다음 블록의 prevBlockHash를 안다 (계획 4.3) */
    private final Object submitLock = new Object();

    public NodeService(Ledger ledger, RaftNodeHolder raft, MemberRegistry registry, NodeSettings settings,
                       PeerClient peers, TeamLeaderService team) {
        this.ledger = ledger;
        this.raft = raft;
        this.registry = registry;
        this.settings = settings;
        this.peers = peers;
        this.team = team;
    }

    // ------------------------------------------------------------------ 기록 (FairChain → 노드)

    /**
     * @param forwarded 다른 노드가 넘겨준 요청이면 true — 다시 넘기지 않는다
     */
    public BlockResultDto submit(BlockRecordDto dto, boolean forwarded) {
        if (dto == null || !Ledger.validRecord(dto.recordKey(), dto.recordHash(), dto.version())) {
            throw new IllegalArgumentException("Invalid record key/hash/version");
        }
        if (!raft.running()) {
            return BlockResultDto.rejected(raft.outReason() != null ? ReasonCode.CHAIN_INVALID : ReasonCode.NOT_LEADER);
        }
        if (registry.halted()) {
            // 서명이 둘 다 맞는 서로 다른 블록이 나왔다 — 관리자가 확인하고 재개할 때까지 기록하지 않는다 (11/06 변경안 6장)
            return BlockResultDto.rejected(ReasonCode.DIVERGED);
        }
        if (raft.isLeader()) {
            return submitAsLeader(dto);
        }
        if (forwarded) {
            return BlockResultDto.rejected(ReasonCode.NOT_LEADER);
        }
        return forwardToLeader(dto);
    }

    private BlockResultDto submitAsLeader(BlockRecordDto dto) {
        synchronized (submitLock) {
            try {
                if (!raft.awaitReady(READY_TIMEOUT)) {
                    return BlockResultDto.rejected(ReasonCode.NOT_LEADER);
                }
                for (int attempt = 0; attempt < SUBMIT_ATTEMPTS; attempt++) {
                    Ledger.Head head = raft.stateMachine().logHead();
                    if (!head.equals(ledger.head())) {
                        // 내 H2가 아직 로그를 따라오는 중 (드문 경우: 막 스냅샷을 받음)
                        return BlockResultDto.rejected(ReasonCode.BEHIND);
                    }
                    Optional<Block> latest = ledger.latestFor(dto.recordKey());
                    if (latest.isPresent()) {
                        Block b = latest.get();
                        if (b.version.equals(dto.version()) && b.recordHash.equals(dto.recordHash())) {
                            return new BlockResultDto(true, b.blockIndex, ReasonCode.OK);     // 같은 기록 → 기존 블록
                        }
                        if (dto.version() <= b.version) {
                            return BlockResultDto.rejected(ReasonCode.INVALID_BLOCK);
                        }
                    }
                    BlockProposalDto block = ledger.build(head.index() + 1, head.hash(), dto.recordKey(),
                            dto.version(), dto.recordHash());
                    Object result = raft.replicate(new LedgerOp.AppendBlock(block), settings.commitTimeout());
                    if (result instanceof ApplyResult r) {
                        if (r.ok()) {
                            return new BlockResultDto(true, r.blockIndex(), ReasonCode.OK);
                        }
                        if (r.reason() != ReasonCode.ALREADY_VOTED) {
                            return BlockResultDto.rejected(r.reason());
                        }
                        // 그사이 같은 번호가 먼저 확정됨 — 새 머리로 다시 만든다
                    }
                }
                return BlockResultDto.rejected(ReasonCode.ALREADY_VOTED);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return BlockResultDto.rejected(ReasonCode.NOT_LEADER);
            } catch (TimeoutException e) {
                // 과반이 시간 안에 저장하지 않음. 나중에 확정될 수도 있다 — 같은 기록을 다시 보내면 같은 블록 번호를 돌려준다
                return BlockResultDto.rejected(ReasonCode.TIMEOUT);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof NotLeaderException) {
                    return BlockResultDto.rejected(ReasonCode.NOT_LEADER);
                }
                if (cause instanceof IndeterminateStateException) {
                    return BlockResultDto.rejected(ReasonCode.TIMEOUT);
                }
                if (cause instanceof CannotReplicateException) {
                    return BlockResultDto.rejected(ReasonCode.NOT_LEADER);
                }
                log.warn("블록 복제 실패: {}", String.valueOf(cause));
                return BlockResultDto.rejected(ReasonCode.NOT_LEADER);
            }
        }
    }

    /** 팔로워: 리더에게 그대로 넘긴다 (한 번만) */
    private BlockResultDto forwardToLeader(BlockRecordDto dto) {
        String leader = awaitLeader();
        if (leader == null || leader.equals(settings.id())) {
            return BlockResultDto.rejected(ReasonCode.NOT_LEADER);
        }
        return peers.forward(leader, BlockRecordApi.RECORDS, dto, BlockResultDto.class)
                .orElse(BlockResultDto.rejected(ReasonCode.NOT_LEADER));
    }

    /** 선거 중이면 잠깐 기다린다 */
    private String awaitLeader() {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT.toMillis();
        while (true) {
            Optional<String> leader = raft.leaderId();
            if (leader.isPresent() || System.currentTimeMillis() >= deadline) {
                return leader.orElse(null);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ 노드 ↔ 노드: 블록 내려받기

    /**
     * 다른 노드가 따라잡을 수 있게 확정 블록을 번호 순으로 내준다 (내 서명 포함).
     * 빠진 노드 · 체인이 깨진 노드는 내주지 않고 503으로 답한다 — 받는 쪽은 "줄 블록 없음"과 구분해 다른 노드로 간다.
     */
    public List<BlockProposalDto> blocks(Long fromIndex, int limit) {
        if (raft.outReason() != null || raft.chainBroken() || ledger.chainBroken()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "This node is out of the leader layer");
        }
        if (fromIndex == null || fromIndex < 1) {
            return List.of();
        }
        return ledger.blocks(fromIndex, limit);
    }

    /** 판정 때 이진 탐색용 — 빠진 노드도 답한다 (판정의 증거라서). 저장된 값을 그대로 돌려준다 */
    public BlockHashDto blockHash(Long index) {
        long last = ledger.head().index();
        if (index == null || index < 1) {
            return new BlockHashDto(index, null, last);
        }
        return new BlockHashDto(index, ledger.block(index).map(b -> b.blockHash).orElse(null), last);
    }

    // ------------------------------------------------------------------ 검증 (FairChain → 노드)

    /** 빠진 노드(제외 · 체인 손상 · 복구 중)는 검증에 답하지 않는다 — FairChain은 "응답 없음"으로 센다 (계획 4.8) */
    private void requireIn() {
        if (raft.outReason() != null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "This node is out: " + raft.outReason());
        }
    }

    public BlockVerifyResultDto verify(BlockVerifyDto dto) {
        requireIn();
        long last = ledger.head().index();
        if (dto == null || !Ledger.validRecord(dto.recordKey(), dto.recordHash(), dto.version())) {
            return new BlockVerifyResultDto(false, null, null, last);
        }
        // 답하는 블록 하나만 검사한다 (해시 · 앞뒤 연결). 체인 전체를 조회마다 다시 읽지 않는다 — Ledger.blockIntact 참고
        return ledger.latestFor(dto.recordKey())
                .map(b -> new BlockVerifyResultDto(
                        ledger.blockIntact(b) && b.version.equals(dto.version()) && b.recordHash.equals(dto.recordHash()),
                        b.version, b.recordHash, last))
                .orElse(new BlockVerifyResultDto(false, null, null, last));
    }

    public BlockBatchVerifyResponseDto batch(BlockBatchVerifyRequestDto dto) {
        if (dto == null || dto.records() == null) {
            throw new IllegalArgumentException("Missing records");
        }
        requireIn();
        long last = ledger.head().index();
        return new BlockBatchVerifyResponseDto(dto.records().stream().map(r -> {
            if (r == null || r.blockIndex() == null) {
                return new BlockVerifyItemResultDto(null, false, false);
            }
            Optional<Block> block = ledger.block(r.blockIndex());
            boolean matched = block.map(b -> Objects.equals(b.recordKey, r.recordKey())
                    && Objects.equals(b.version, r.version())
                    && Objects.equals(b.recordHash, r.recordHash())).orElse(false);
            // 묻는 블록마다 그 블록만 검사한다 (전에는 요청마다 체인 전체를 한 번 읽었다)
            return new BlockVerifyItemResultDto(r.blockIndex(), matched, block.map(ledger::blockIntact).orElse(false));
        }).toList(), last);
    }

    public BlockProofDto proof(String recordKey) {
        if (recordKey == null || !Ledger.HEX.matcher(recordKey).matches()) {
            return new BlockProofDto(false, null, null, null, null);
        }
        requireIn();
        return ledger.latestFor(recordKey)
                .map(b -> new BlockProofDto(ledger.blockIntact(b), b.blockIndex, b.version, b.recordHash, b.blockHash))
                .orElse(new BlockProofDto(false, null, null, null, null));
    }

    /** 저장된 블록을 번호 순으로 그대로 보여 준다 (조작된 블록도 그대로) */
    public List<BlockDto> ledgerBlocks(Long fromIndex, int limit) {
        if (fromIndex == null) {
            return List.of();
        }
        return ledger.storedBlocks(fromIndex, limit);
    }

    /**
     * 원장 상태. 체인 전체의 해시를 다시 검사하므로 조작 직후에도 바로 드러난다. 체인이 깨졌으면 이 노드는 리더 층에서 빠진다.
     * 켤 때 서명 검사로 깨진 것이 확인된 노드도(해시를 다시 맞춰 놓은 조작) 복구 전까지 chainValid=false다.
     */
    public NodeLedgerStatusDto ledgerStatus() {
        boolean valid = ledger.audit();
        if (!valid) {
            raft.chainInvalid("원장 상태 조회 중 체인 전체 검사 실패");
        }
        Ledger.Head head = ledger.head();
        return new NodeLedgerStatusDto(head.index(), head.hash(), valid && !raft.chainBroken(), List.of());
    }

    // ------------------------------------------------------------------ 상태 (운영자 · 점검 화면)

    public NodeStatusDto status() {
        Ledger.Head head = ledger.head();
        Optional<RaftTerm> term = raft.term();
        List<String> voting = raft.committedMembers().map(RaftGroupMembers::getVotingMembers)
                .map(NodeService::ids).orElse(List.of());
        List<String> all = raft.committedMembers().map(RaftGroupMembers::getMembers)
                .map(NodeService::ids).orElse(List.of());
        List<String> learners = all.stream().filter(id -> !voting.contains(id)).toList();
        List<MemberInfoDto> members = new ArrayList<>();
        for (MemberRecord m : registry.all()) {
            boolean listed = all.isEmpty() || all.contains(m.id());
            if (listed) {
                members.add(new MemberInfoDto(m.id(), m.team(), m.url(), m.publicKey(),
                        voting.isEmpty() || voting.contains(m.id()), registry.isExcluded(m.id())));
            }
        }
        return new NodeStatusDto(settings.id(), settings.team(), settings.role(), raftRole(),
                term.map(RaftTerm::getTerm).orElse(null), raft.leaderId().orElse(null),
                members, learners, registry.excludedIds(), registry.verdicts(), head.index(), head.hash(),
                raft.keys().publicKey(), settings.teamLeader(), team.members(), raft.outReason(), registry.halted());
    }

    /** LEADER · FOLLOWER · CANDIDATE · LEARNER · NONE — 메모리에 있는 값만 본다 (상태 로그도 쓴다) */
    public String raftRole() {
        if (!settings.leaderLayer() || !raft.running()) {
            return "NONE";
        }
        if (raft.isLeader()) {
            return "LEADER";
        }
        List<String> voting = raft.committedMembers().map(RaftGroupMembers::getVotingMembers)
                .map(NodeService::ids).orElse(List.of());
        if (!voting.isEmpty() && !voting.contains(settings.id())) {
            return "LEARNER";
        }
        return raft.leaderId().isPresent() ? "FOLLOWER" : "CANDIDATE";
    }

    private static List<String> ids(java.util.Collection<RaftEndpoint> endpoints) {
        return endpoints.stream().map(e -> String.valueOf(e.getId())).toList();
    }
}
