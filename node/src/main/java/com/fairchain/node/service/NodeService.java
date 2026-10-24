package com.fairchain.node.service;

import com.fairchain.api.util.Sha256RecordHasher;
import com.fairchain.node.entity.Block;
import com.fairchain.node.entity.PendingBlock;
import com.fairchain.node.repository.BlockRepository;
import com.fairchain.node.repository.PendingBlockRepository;
import com.fairchain.nodeapi.NodeConsensusApi;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.dto.BlockBatchVerifyRequestDto;
import com.fairchain.nodeapi.dto.BlockBatchVerifyResponseDto;
import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.BlockProofDto;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyItemResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 블록 기록 · 노드 간 합의 · 검증.
 *
 * 합의 (PBFT 아님): 제안(PREPARE) → 과반 찬성 → 확정 결정(DECISION) → 확정(CONFIRM).
 * 3대 중 2대가 찬성하면 확정하므로 노드 1대가 꺼져 있어도 기록이 멈추지 않는다.
 * 꺼져 있던 노드는 다른 노드의 확정 블록을 받아 따라잡는다 (synchronize).
 *
 * 각 노드는 블록 번호 하나에 제안 하나에만 찬성하고(pending_blocks) 그 표를 바꾸지 않는다.
 * 그래서 같은 번호에 서로 다른 블록이 확정될 수 없고, 끝나지 못한 제안은 누구든 다시 돌려 마무리할 수 있다 (settlePending).
 */
@Service
public class NodeService {

    private static final Logger log = LoggerFactory.getLogger(NodeService.class);

    private static final String GENESIS = "0".repeat(64);
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
    /** 3대 중 과반 */
    private static final int QUORUM = 2;
    private static final int PAGE = 200;
    private static final int SUBMIT_ATTEMPTS = 2;
    private static final BlockResultDto REJECTED = new BlockResultDto(false, null);

    private final BlockRepository repo;
    private final PendingBlockRepository pendingRepo;
    private final RestClient http;
    private final Sha256RecordHasher hasher = new Sha256RecordHasher();
    private final List<String> peers;
    private final String secret;

    /** 원장(Block · pending_blocks)을 바꾸는 동작이 잡는 잠금. 다른 노드를 호출하는 동안에는 잡지 않는다 */
    private final Object ledgerLock = new Object();
    /** 이 노드가 주도하는 합의는 한 번에 하나 */
    private final Object proposerLock = new Object();

    /** 여기까지는 해시 · 연결을 확인했다 — 투표할 때마다 체인 전체를 다시 읽지 않기 위한 표시 */
    private long verifiedIndex = 0;
    private String verifiedHash = GENESIS;
    /** 체인이 깨진 것이 확인되면 true. 투표 · 제안 · 블록 제공을 멈춘다 (다음 전체 검사에서 다시 판정) */
    private volatile boolean chainBroken = false;

    @Autowired
    public NodeService(BlockRepository repo, PendingBlockRepository pendingRepo,
                       @Value("${node.peers:}") String peers, @Value("${node.secret}") String secret,
                       @Value("${node.peer-timeout-ms:800}") int peerTimeoutMs) {
        this.repo = repo;
        this.pendingRepo = pendingRepo;
        this.peers = Arrays.stream(peers.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
        this.secret = secret;
        if (this.peers.size() != 2) {
            throw new IllegalStateException("Exactly two peers required for three-node demo");
        }
        // FairChain의 노드 호출 제한(2초) 안에 한 번의 합의가 끝나도록 노드 간 호출은 짧게 끊는다
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(peerTimeoutMs);
        factory.setReadTimeout(peerTimeoutMs);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public NodeService(BlockRepository repo, PendingBlockRepository pendingRepo, String peers, String secret) {
        this(repo, pendingRepo, peers, secret, 800);
    }

    // ------------------------------------------------------------------ 기록 (FairChain → 노드)

    /** 블록을 만들어 다른 노드에 제안하고 과반이 찬성하면 확정. 같은 기록을 다시 보내도 블록은 하나만 생긴다 */
    public BlockResultDto submit(BlockRecordDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Missing record");
        }
        validateKey(dto.recordKey(), dto.recordHash(), dto.version());
        synchronized (proposerLock) {
            for (int attempt = 0; attempt < SUBMIT_ATTEMPTS; attempt++) {
                if (attempt > 0) {
                    synchronize();      // 뒤처졌거나 다른 블록이 먼저 확정됐을 수 있다
                }
                synchronized (ledgerLock) {
                    if (!chainIntact()) {
                        return REJECTED;
                    }
                }
                if (!settlePending()) {
                    continue;           // 남은 제안을 아직 끝낼 수 없다 (과반과 연결되지 않음)
                }
                Optional<Block> existing = repo.findTopByRecordKeyOrderByBlockIndexDesc(dto.recordKey());
                if (existing.isPresent()) {
                    Block latest = existing.get();
                    if (latest.version.equals(dto.version()) && latest.recordHash.equals(dto.recordHash())) {
                        return new BlockResultDto(true, latest.blockIndex);
                    }
                    if (dto.version() <= latest.version) {
                        return REJECTED;
                    }
                }
                BlockProposalDto proposal = newProposal(dto);
                if (startRound(proposal)) {
                    return new BlockResultDto(true, proposal.blockIndex());
                }
            }
            return REJECTED;
        }
    }

    private BlockProposalDto newProposal(BlockRecordDto dto) {
        // 초 단위로 자른다 — 블록 해시에 들어가는 값이라 DB 시각 정밀도에 따라 달라지면 안 된다
        long time = System.currentTimeMillis() / 1000 * 1000;
        BlockProposalDto unsigned = new BlockProposalDto(next(), prev(), dto.recordKey(), dto.version(), dto.recordHash(), time, "");
        return new BlockProposalDto(unsigned.blockIndex(), unsigned.prevBlockHash(), unsigned.recordKey(),
                unsigned.version(), unsigned.recordHash(), time, hash(unsigned));
    }

    /** 자기 표를 먼저 저장한 뒤 다른 노드에 제안한다 */
    private boolean startRound(BlockProposalDto proposal) {
        synchronized (ledgerLock) {
            if (currentPending().isPresent() || !validProposal(proposal)) {
                return false;
            }
            pendingRepo.saveAndFlush(toPending(proposal));
        }
        return completeRound(proposal, false);
    }

    /**
     * 준비된 제안을 확정까지 끌고 간다. 과반을 얻지 못하면 표는 남겨 두고 false (다음 시도 · 주기 점검에서 다시 돌린다).
     *
     * @param decided 이미 확정 결정이 저장된 제안이면 true — 과반이 찬성했던 블록이므로 다시 세지 않는다
     */
    private boolean completeRound(BlockProposalDto proposal, boolean decided) {
        List<String> approvers = new ArrayList<>();
        for (String peer : peers) {
            if (peerPost(peer, NodeConsensusApi.PROPOSALS, proposal)) {
                approvers.add(peer);
            }
        }
        if (!decided && 1 + approvers.size() < QUORUM) {
            return false;
        }
        Long index = proposal.blockIndex();
        String hashParam = "?blockHash=" + proposal.blockHash();
        // 확정 결정을 저장한 뒤에 알린다. 여기서 꺼져도 같은 블록으로만 복구된다
        if (!recordCommitDecision(index, proposal.blockHash())) {
            return false;
        }
        for (String peer : approvers) {
            peerPost(peer, NodeConsensusApi.decisionPath(index) + hashParam, null);
        }
        if (!confirm(index, proposal.blockHash())) {
            return false;
        }
        // 찬성하지 않은 노드에는 보내지 않는다 — 나중에 synchronize로 따라온다
        for (String peer : approvers) {
            peerPost(peer, NodeConsensusApi.confirmPath(index) + hashParam, null);
        }
        return true;
    }

    /** 확정되지 못하고 남은 제안을 다시 돌려 끝낸다. 남은 것이 없으면 true */
    public boolean settlePending() {
        synchronized (proposerLock) {
            Optional<PendingBlock> pending;
            synchronized (ledgerLock) {
                pending = currentPending();
            }
            return pending.map(p -> completeRound(toProposal(p), p.commitDecided)).orElse(true);
        }
    }

    /** [운영자 수동] 남아 있는 제안을 지정해서 다시 돌린다. 새 블록 · 새 해시는 만들지 않는다 */
    public BlockResultDto retryPrepared(Long index, String expectedHash) {
        if (index == null || expectedHash == null || !HEX.matcher(expectedHash).matches()) {
            return REJECTED;
        }
        synchronized (proposerLock) {
            PendingBlock saved;
            synchronized (ledgerLock) {
                Optional<Block> committed = repo.findById(index);
                if (committed.isPresent()) {
                    return expectedHash.equals(committed.get().blockHash) ? new BlockResultDto(true, index) : REJECTED;
                }
                saved = pendingRepo.findById(index).orElse(null);
                if (saved == null || !expectedHash.equals(saved.blockHash) || !validProposal(toProposal(saved))) {
                    return REJECTED;
                }
            }
            return completeRound(toProposal(saved), saved.commitDecided) ? new BlockResultDto(true, index) : REJECTED;
        }
    }

    // ------------------------------------------------------------------ 합의 (노드 ↔ 노드)

    /** 제안 수신 — 찬성하면 표를 저장하고 true. 체인이 깨진 노드는 찬성하지 않는다 */
    public boolean propose(BlockProposalDto p) {
        if (p == null || p.blockIndex() == null || p.blockHash() == null) {
            return false;
        }
        if (p.blockIndex() > next()) {
            synchronize();      // 뒤처져 있으면 먼저 따라잡는다
        }
        synchronized (ledgerLock) {
            // 이미 확정된 번호 — 같은 블록의 재전송이면 찬성, 다른 블록이면 반대
            Optional<Block> committed = repo.findById(p.blockIndex());
            if (committed.isPresent()) {
                return sameBlock(committed.get(), p);
            }
            if (!chainIntact() || !validProposal(p)) {
                return false;
            }
            Optional<PendingBlock> pending = currentPending();
            if (pending.isPresent()) {
                // 번호 하나에 표는 하나. 블록 해시에 시각이 없으므로, 같은 기록을 다른 노드가 다른 시각에 제안해도 같은 블록이다
                return pending.get().blockHash.equals(p.blockHash());
            }
            pendingRepo.saveAndFlush(toPending(p));
            return true;
        }
    }

    /** 확정 결정 저장. 맞는 제안을 가지고 있을 때만 true */
    public boolean recordCommitDecision(Long index, String expectedHash) {
        if (index == null || expectedHash == null) {
            return false;
        }
        synchronized (ledgerLock) {
            Optional<PendingBlock> item = pendingRepo.findById(index);
            if (item.isEmpty() || !expectedHash.equals(item.get().blockHash)) {
                return false;
            }
            if (!validProposal(toProposal(item.get()))) {
                return false;
            }
            if (!item.get().commitDecided) {
                item.get().commitDecided = true;
                pendingRepo.saveAndFlush(item.get());
            }
            return true;
        }
    }

    /** 확정 결정이 저장된 제안을 블록으로 확정. 이미 같은 블록이 확정돼 있으면 true */
    public boolean confirm(Long index, String expectedHash) {
        if (index == null || expectedHash == null || !HEX.matcher(expectedHash).matches()) {
            return false;
        }
        synchronized (ledgerLock) {
            Optional<Block> committed = repo.findById(index);
            if (committed.isPresent()) {
                return expectedHash.equals(committed.get().blockHash);
            }
            Optional<PendingBlock> prepared = pendingRepo.findById(index);
            if (prepared.isEmpty()) {
                return false;
            }
            BlockProposalDto p = toProposal(prepared.get());
            if (!expectedHash.equals(p.blockHash()) || !prepared.get().commitDecided || !validProposal(p)) {
                return false;
            }
            // 블록을 먼저 저장한다. 표 삭제 전에 꺼져도 번호가 지난 표는 currentPending에서 정리된다
            repo.saveAndFlush(toEntity(p));
            pendingRepo.delete(prepared.get());
            return true;
        }
    }

    /** 다른 노드가 따라잡을 수 있게 확정 블록을 번호 순으로 내준다. 체인이 깨진 노드는 내주지 않는다 */
    public List<BlockProposalDto> blocks(Long fromIndex, int limit) {
        if (fromIndex == null || fromIndex < 1 || chainBroken) {
            return List.of();
        }
        int size = Math.max(1, Math.min(limit, PAGE));
        return repo.findByBlockIndexGreaterThanEqualOrderByBlockIndexAsc(fromIndex, PageRequest.of(0, size))
                .stream().map(this::toProposal).toList();
    }

    /** 다른 노드의 확정 블록을 받아 뒤처진 체인을 따라잡는다. 추가한 블록 수를 돌려준다 */
    public int synchronize() {
        int appended = 0;
        for (String peer : peers) {
            while (true) {
                List<BlockProposalDto> page = peerBlocks(peer, next());
                int added = appendCommitted(page);
                appended += added;
                if (added == 0 || page.size() < PAGE) {
                    break;
                }
            }
        }
        if (appended > 0) {
            log.info("다른 노드에서 블록 {}개를 받아 따라잡음 (높이 {})", appended, next() - 1);
        }
        return appended;
    }

    private int appendCommitted(List<BlockProposalDto> page) {
        int added = 0;
        synchronized (ledgerLock) {
            if (page.isEmpty() || !chainIntact()) {
                return 0;       // 깨진 체인 위에는 쌓지 않는다
            }
            for (BlockProposalDto p : page) {
                if (!validProposal(p)) {
                    break;      // 내 체인에 이어지지 않거나 해시가 맞지 않음
                }
                repo.saveAndFlush(toEntity(p));
                added++;
            }
            if (added > 0) {
                currentPending();   // 이미 확정된 번호의 표 정리
            }
        }
        return added;
    }

    /** 주기 점검: 체인 전체 검사 → 따라잡기 → 남은 제안 마무리 */
    public void maintain() {
        boolean wasBroken = chainBroken;
        if (!auditChain()) {
            if (!wasBroken) {
                log.error("체인 무결성 검사 실패 — 이 노드는 투표 · 제안에서 빠진다");
            }
            return;
        }
        synchronize();
        settlePending();
    }

    // ------------------------------------------------------------------ 검증 (FairChain → 노드)

    public BlockVerifyResultDto verify(BlockVerifyDto dto) {
        if (dto == null || dto.recordKey() == null || dto.version() == null || dto.recordHash() == null
                || !HEX.matcher(dto.recordKey()).matches() || !HEX.matcher(dto.recordHash()).matches() || dto.version() < 1) {
            return new BlockVerifyResultDto(false, null, null);
        }
        return repo.findTopByRecordKeyOrderByBlockIndexDesc(dto.recordKey())
                .map(b -> new BlockVerifyResultDto(
                        integrity(b) && b.version.equals(dto.version()) && b.recordHash.equals(dto.recordHash()),
                        b.version, b.recordHash))
                .orElse(new BlockVerifyResultDto(false, null, null));
    }

    public BlockBatchVerifyResponseDto batch(BlockBatchVerifyRequestDto dto) {
        if (dto == null || dto.records() == null) {
            throw new IllegalArgumentException("Missing records");
        }
        boolean chainValid = auditChain();      // 요청 한 번에 한 번만 검사
        return new BlockBatchVerifyResponseDto(dto.records().stream().map(r -> {
            if (r == null || r.blockIndex() == null) {
                return new BlockVerifyItemResultDto(null, false, false);
            }
            Optional<Block> block = repo.findById(r.blockIndex());
            boolean matched = block.map(b -> Objects.equals(b.recordKey, r.recordKey())
                    && Objects.equals(b.version, r.version())
                    && Objects.equals(b.recordHash, r.recordHash())).orElse(false);
            return new BlockVerifyItemResultDto(r.blockIndex(), matched, block.isPresent() && chainValid);
        }).toList());
    }

    public BlockProofDto proof(String recordKey) {
        if (recordKey == null || !HEX.matcher(recordKey).matches()) {
            return new BlockProofDto(false, null, null, null, null);
        }
        return repo.findTopByRecordKeyOrderByBlockIndexDesc(recordKey)
                .map(b -> new BlockProofDto(integrity(b), b.blockIndex, b.version, b.recordHash, b.blockHash))
                .orElse(new BlockProofDto(false, null, null, null, null));
    }

    /** 저장된 블록을 번호 순으로 그대로 보여 준다 (조작된 블록도 그대로) */
    public List<BlockDto> ledgerBlocks(Long fromIndex, int limit) {
        if (fromIndex == null) {
            return List.of();
        }
        int size = Math.max(1, Math.min(limit, PAGE));
        return repo.findByBlockIndexGreaterThanEqualOrderByBlockIndexAsc(Math.max(1L, fromIndex), PageRequest.of(0, size)).stream()
                .map(b -> new BlockDto(b.blockIndex, b.prevBlockHash, b.recordKey, b.version, b.recordHash, b.timestamp, b.blockHash))
                .toList();
    }

    /** 원장 상태. 체인 전체를 다시 검사하므로 조작 직후에도 바로 드러난다 */
    public NodeLedgerStatusDto ledgerStatus() {
        Optional<Block> head = repo.findTopByOrderByBlockIndexDesc();
        List<Long> prepared = pendingRepo.findAll().stream().map(x -> x.blockIndex).sorted().toList();
        if (head.isEmpty()) {
            return new NodeLedgerStatusDto(0L, GENESIS, true, prepared);
        }
        Block b = head.get();
        return new NodeLedgerStatusDto(b.blockIndex, b.blockHash, integrity(b), prepared);
    }

    // ------------------------------------------------------------------ 체인 무결성

    /** 그 블록까지 포함해 체인 전체가 정상인지. 마지막 고리만 보면 오래된 블록의 조작을 놓친다 */
    private boolean integrity(Block target) {
        if (target == null || target.blockIndex == null || target.blockIndex < 1) {
            return false;
        }
        ChainScan scan = scan(1, GENESIS);
        applyAudit(scan);
        return scan.valid() && scan.lastIndex() >= target.blockIndex;
    }

    /** 처음부터 전체 검사 */
    private boolean auditChain() {
        ChainScan scan = scan(1, GENESIS);
        applyAudit(scan);
        return scan.valid();
    }

    private void applyAudit(ChainScan scan) {
        synchronized (ledgerLock) {
            chainBroken = !scan.valid();
            if (scan.valid()) {
                verifiedIndex = scan.lastIndex();
                verifiedHash = scan.lastHash();
            }
        }
    }

    /** 투표 · 제안 전 검사. 이미 확인한 구간 뒤쪽만 읽는다 (전체 검사는 주기 점검과 조회에서). ledgerLock을 잡고 호출 */
    private boolean chainIntact() {
        if (chainBroken) {
            return false;
        }
        ChainScan scan = scan(verifiedIndex + 1, verifiedHash);
        if (!scan.valid()) {
            chainBroken = true;
            return false;
        }
        verifiedIndex = scan.lastIndex();
        verifiedHash = scan.lastHash();
        return true;
    }

    private record ChainScan(boolean valid, long lastIndex, String lastHash) {}

    /** from번 블록부터 끝까지 번호 · 이전 해시 연결 · 블록 해시를 다시 계산해 확인 */
    private ChainScan scan(long from, String expectedPrevious) {
        long index = from;
        String previous = expectedPrevious;
        while (true) {
            List<Block> page = repo.findByBlockIndexGreaterThanEqualOrderByBlockIndexAsc(index, PageRequest.of(0, PAGE));
            for (Block b : page) {
                if (b.blockIndex == null || b.blockIndex != index || b.timestamp == null
                        || b.prevBlockHash == null || b.blockHash == null || b.version == null
                        || b.recordKey == null || b.recordHash == null) {
                    return new ChainScan(false, index - 1, previous);
                }
                if (!previous.equals(b.prevBlockHash) || !hash(toProposal(b)).equals(b.blockHash)) {
                    return new ChainScan(false, index - 1, previous);
                }
                try {
                    validateKey(b.recordKey, b.recordHash, b.version);
                } catch (IllegalArgumentException ex) {
                    return new ChainScan(false, index - 1, previous);
                }
                previous = b.blockHash;
                index++;
            }
            if (page.size() < PAGE) {
                return new ChainScan(true, index - 1, previous);
            }
        }
    }

    // ------------------------------------------------------------------ 내부 도구

    /**
     * 블록 해시에는 제안 시각을 넣지 않는다. 같은 번호에 같은 기록을 노드마다 다른 시각에 제안하면(노드가 꺼졌다 켜지며 재시도)
     * 시각이 들어간 해시는 서로 달라져 각자 자기 제안에만 투표하고 영원히 확정되지 않는다. 시각은 블록에 기록만 한다.
     */
    private String hash(BlockProposalDto p) {
        return hasher.hash(p.blockIndex().toString(), p.prevBlockHash(), p.recordKey(), p.version().toString(),
                p.recordHash());
    }

    private long next() {
        return repo.findTopByOrderByBlockIndexDesc().map(b -> b.blockIndex + 1).orElse(1L);
    }

    private String prev() {
        return repo.findTopByOrderByBlockIndexDesc().map(b -> b.blockHash).orElse(GENESIS);
    }

    private void validateKey(String key, String hash, Long version) {
        if (key == null || !HEX.matcher(key).matches() || hash == null || !HEX.matcher(hash).matches()
                || version == null || version < 1) {
            throw new IllegalArgumentException("Invalid record key/hash/version");
        }
    }

    /** 내 체인 바로 다음에 이어지는 올바른 블록인지: 번호 · 이전 해시 · 블록 해시 · 버전 증가 */
    private boolean validProposal(BlockProposalDto p) {
        if (p == null || p.blockIndex() == null || p.version() == null || p.timestamp() == null || p.blockHash() == null) {
            return false;
        }
        try {
            validateKey(p.recordKey(), p.recordHash(), p.version());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return p.blockIndex().equals(next()) && prev().equals(p.prevBlockHash()) && hash(p).equals(p.blockHash())
                && repo.findTopByRecordKeyOrderByBlockIndexDesc(p.recordKey()).map(b -> p.version() > b.version).orElse(true);
    }

    /** 다음 번호에 대한 이 노드의 표. 번호가 이미 지난 표는 지운다. ledgerLock을 잡고 호출 */
    private Optional<PendingBlock> currentPending() {
        long next = next();
        for (PendingBlock p : pendingRepo.findAll()) {
            if (!Objects.equals(p.blockIndex, next)) {
                pendingRepo.delete(p);
            }
        }
        return pendingRepo.findById(next);
    }

    private boolean sameBlock(Block b, BlockProposalDto p) {
        return p.blockHash().equals(b.blockHash)
                && Objects.equals(p.recordKey(), b.recordKey)
                && Objects.equals(p.version(), b.version)
                && Objects.equals(p.recordHash(), b.recordHash)
                && Objects.equals(p.prevBlockHash(), b.prevBlockHash);
    }

    private BlockProposalDto toProposal(PendingBlock p) {
        return new BlockProposalDto(p.blockIndex, p.prevBlockHash, p.recordKey, p.version, p.recordHash, p.timestamp, p.blockHash);
    }

    private BlockProposalDto toProposal(Block b) {
        return new BlockProposalDto(b.blockIndex, b.prevBlockHash, b.recordKey, b.version, b.recordHash,
                b.timestamp.toInstant(ZoneOffset.UTC).toEpochMilli(), b.blockHash);
    }

    private PendingBlock toPending(BlockProposalDto p) {
        return new PendingBlock(p.blockIndex(), p.prevBlockHash(), p.recordKey(), p.version(), p.recordHash(), p.timestamp(), p.blockHash());
    }

    private Block toEntity(BlockProposalDto p) {
        return new Block(p.blockIndex(), p.prevBlockHash(), p.recordKey(), p.version(), p.recordHash(),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(p.timestamp()), ZoneOffset.UTC), p.blockHash());
    }

    private boolean peerPost(String peer, String path, Object body) {
        try {
            var request = http.post().uri(peer + path).header(NodeHeaders.NODE_SECRET, secret);
            return Boolean.TRUE.equals((body == null ? request : request.body(body)).retrieve().body(Boolean.class));
        } catch (Exception e) {
            return false;
        }
    }

    private List<BlockProposalDto> peerBlocks(String peer, long fromIndex) {
        try {
            BlockProposalDto[] page = http.get()
                    .uri(peer + NodeConsensusApi.BLOCKS + "?from=" + fromIndex + "&limit=" + PAGE)
                    .header(NodeHeaders.NODE_SECRET, secret)
                    .retrieve().body(BlockProposalDto[].class);
            return page == null ? List.of() : Arrays.asList(page);
        } catch (Exception e) {
            return List.of();
        }
    }
}
