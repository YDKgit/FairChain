package com.fairchain.node.service;

import com.fairchain.api.util.Sha256RecordHasher;
import com.fairchain.node.entity.Block;
import com.fairchain.node.repository.BlockRepository;
import com.fairchain.nodeapi.dto.BlockBatchVerifyRequestDto;
import com.fairchain.nodeapi.dto.BlockBatchVerifyResponseDto;
import com.fairchain.nodeapi.dto.BlockProofDto;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyItemResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 블록 기록 · 검증. 노드는 자기 DB만 보고 독립적으로 동작하며 다른 노드와 통신하지 않는다.
 *
 * FairChain이 노드 3대에 같은 기록을 같은 순서로 보내고, 2대 이상이 같은 결과를 돌려주면 확정으로 본다 (다수결은 FairChain).
 * 블록 해시에는 노드 시각을 넣지 않으므로, 같은 순서로 기록을 받은 노드들의 체인(블록 번호 · 해시)은 서로 같아진다.
 */
@Service
public class NodeService {

    private static final Logger log = LoggerFactory.getLogger(NodeService.class);

    private static final String GENESIS = "0".repeat(64);
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
    private static final int PAGE = 200;
    private static final BlockResultDto REJECTED = new BlockResultDto(false, null);

    private final BlockRepository repo;
    private final Sha256RecordHasher hasher = new Sha256RecordHasher();

    /** 체인을 바꾸는 동작은 한 번에 하나 */
    private final Object ledgerLock = new Object();

    /** 여기까지는 해시 · 연결을 확인했다 — 기록할 때마다 체인 전체를 다시 읽지 않기 위한 표시 */
    private long verifiedIndex = 0;
    private String verifiedHash = GENESIS;
    /** 체인이 깨진 것이 확인되면 true. 새 기록을 받지 않는다 (다음 전체 검사에서 다시 판정) */
    private volatile boolean chainBroken = false;

    public NodeService(BlockRepository repo) {
        this.repo = repo;
    }

    // ------------------------------------------------------------------ 기록

    /**
     * 자기 체인 끝에 블록을 추가한다. 같은 기록을 다시 받으면 새 블록 없이 기존 블록 번호를 돌려준다.
     * 거절: 그 티켓의 최신 버전보다 크지 않은 버전, 체인이 깨진 노드.
     */
    public BlockResultDto submit(BlockRecordDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Missing record");
        }
        validateKey(dto.recordKey(), dto.recordHash(), dto.version());
        synchronized (ledgerLock) {
            if (!chainIntact()) {
                return REJECTED;
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
            Optional<Block> head = repo.findTopByOrderByBlockIndexDesc();
            long index = head.map(b -> b.blockIndex + 1).orElse(1L);
            String prev = head.map(b -> b.blockHash).orElse(GENESIS);
            Block block = new Block(index, prev, dto.recordKey(), dto.version(), dto.recordHash(),
                    LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS),
                    hash(index, prev, dto.recordKey(), dto.version(), dto.recordHash()));
            repo.saveAndFlush(block);
            return new BlockResultDto(true, index);
        }
    }

    // ------------------------------------------------------------------ 검증

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

    /** 원장 상태. 체인 전체를 다시 검사하므로 조작 직후에도 바로 드러난다. 노드끼리 합의하지 않으므로 미확정 제안은 없다 */
    public NodeLedgerStatusDto ledgerStatus() {
        Optional<Block> head = repo.findTopByOrderByBlockIndexDesc();
        if (head.isEmpty()) {
            return new NodeLedgerStatusDto(0L, GENESIS, true, List.of());
        }
        Block b = head.get();
        return new NodeLedgerStatusDto(b.blockIndex, b.blockHash, integrity(b), List.of());
    }

    // ------------------------------------------------------------------ 체인 무결성

    /** 처음부터 전체 검사. 주기 점검과 검증 · 상태 조회에서 호출한다 */
    public boolean auditChain() {
        ChainScan scan = scan(1, GENESIS);
        boolean wasBroken;
        synchronized (ledgerLock) {
            wasBroken = chainBroken;
            chainBroken = !scan.valid();
            if (scan.valid()) {
                verifiedIndex = scan.lastIndex();
                verifiedHash = scan.lastHash();
            }
        }
        if (!scan.valid() && !wasBroken) {
            log.error("체인 무결성 검사 실패 ({}번 블록 다음) — 이 노드는 새 기록을 받지 않는다", scan.lastIndex());
        }
        return scan.valid();
    }

    /** 그 블록까지 포함해 체인 전체가 정상인지. 마지막 고리만 보면 오래된 블록의 조작을 놓친다 */
    private boolean integrity(Block target) {
        if (target == null || target.blockIndex == null || target.blockIndex < 1) {
            return false;
        }
        return auditChain() && verifiedIndex >= target.blockIndex;
    }

    /** 기록 전 검사. 이미 확인한 구간 뒤쪽만 읽는다. ledgerLock을 잡고 호출 */
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
                if (!previous.equals(b.prevBlockHash)
                        || !hash(b.blockIndex, b.prevBlockHash, b.recordKey, b.version, b.recordHash).equals(b.blockHash)) {
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

    /** 노드 시각은 넣지 않는다 — 같은 기록을 같은 순서로 받은 노드들은 같은 해시를 얻는다 */
    private String hash(Long index, String prevBlockHash, String recordKey, Long version, String recordHash) {
        return hasher.hash(index.toString(), prevBlockHash, recordKey, version.toString(), recordHash);
    }

    private void validateKey(String key, String hash, Long version) {
        if (key == null || !HEX.matcher(key).matches() || hash == null || !HEX.matcher(hash).matches()
                || version == null || version < 1) {
            throw new IllegalArgumentException("Invalid record key/hash/version");
        }
    }
}
