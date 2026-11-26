package com.fairchain.node.ledger;

import com.fairchain.api.util.Sha256RecordHasher;
import com.fairchain.node.entity.Block;
import com.fairchain.node.repository.BlockRepository;
import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 블록 저장소(H2)와 체인 검사.
 *
 * Raft 로그가 "무엇이 확정됐는지"를 정하고, 이 장부는 확정된 블록을 번호 순으로 담는다. 블록은 바로 앞 블록에
 * 이어질 때만 들어간다 (번호 = 마지막 + 1, prevBlockHash = 마지막 해시, blockHash 재계산 일치, 버전 증가).
 */
@Component
public class Ledger {

    public static final String GENESIS = "0".repeat(64);
    public static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
    private static final int PAGE = 200;

    private final BlockRepository repo;
    private final Sha256RecordHasher hasher = new Sha256RecordHasher();

    /** 장부를 바꾸는 동작이 잡는 잠금 (Raft 적용 스레드 · 따라잡기 스레드) */
    private final Object lock = new Object();
    /** 여기까지는 해시 · 연결을 확인했다 — 블록을 붙일 때마다 체인 전체를 다시 읽지 않기 위한 표시 */
    private long verifiedIndex = 0;
    private String verifiedHash = GENESIS;
    /** 체인이 깨진 것이 확인되면 true (다음 전체 검사에서 다시 판정) */
    private volatile boolean chainBroken = false;
    /** auditSigned: 서명까지 전체 검사한 시각, 서명까지 확인한 마지막 블록 */
    private final Object signedLock = new Object();
    private long signedAuditAt = 0;
    private long signedIndex = 0;
    private String signedHash = GENESIS;

    public Ledger(BlockRepository repo) {
        this.repo = repo;
    }

    public record Head(long index, String hash) {}

    /** 블록을 붙인 결과 */
    public enum Append {
        /** 새로 붙였다 */
        APPENDED,
        /** 같은 블록이 이미 있다 */
        ALREADY_HAVE,
        /** 그 번호에 다른 블록이 있거나, 앞 블록 해시가 다르다 */
        CONFLICT,
        /** 앞 번호 블록이 아직 없다 (뒤처짐) */
        GAP,
        /** 블록 자체가 틀렸다 (형식 · 해시 · 버전) */
        INVALID,
        /** 이 장부의 체인이 깨져 있다 */
        BROKEN
    }

    // ------------------------------------------------------------------ 읽기

    /** 마지막 블록 (번호 · 해시). 기본 키로 두 번 찾는다 — 장부 길이와 상관없이 빠르다 (BlockRepository.maxBlockIndex 참고) */
    public Head head() {
        for (int attempt = 0; attempt < 3; attempt++) {
            Long last = repo.maxBlockIndex();
            if (last == null) {
                return new Head(0, GENESIS);
            }
            Optional<Block> b = repo.findById(last);
            if (b.isPresent()) {
                return new Head(b.get().blockIndex, b.get().blockHash);
            }
            // 두 조회 사이에 복구가 뒤쪽을 지웠다 — 다시 읽는다
        }
        return new Head(0, GENESIS);
    }

    public Optional<Block> block(long index) {
        return repo.findById(index);
    }

    public Optional<Block> latestFor(String recordKey) {
        return repo.findTopByRecordKeyOrderByBlockIndexDesc(recordKey);
    }

    public List<BlockProposalDto> blocks(long fromIndex, int limit) {
        int size = Math.max(1, Math.min(limit, PAGE));
        return repo.findByBlockIndexGreaterThanEqualOrderByBlockIndexAsc(Math.max(1L, fromIndex), PageRequest.of(0, size))
                .stream().map(Ledger::toProposal).toList();
    }

    public List<BlockDto> storedBlocks(long fromIndex, int limit) {
        int size = Math.max(1, Math.min(limit, PAGE));
        return repo.findByBlockIndexGreaterThanEqualOrderByBlockIndexAsc(Math.max(1L, fromIndex), PageRequest.of(0, size))
                .stream()
                .map(b -> new BlockDto(b.blockIndex, b.prevBlockHash, b.recordKey, b.version, b.recordHash, b.timestamp,
                        b.blockHash, b.signerId, b.signature))
                .toList();
    }

    public boolean chainBroken() {
        return chainBroken;
    }

    // ------------------------------------------------------------------ 쓰기

    /** 바로 다음 번호의 블록을 검증해서 붙인다. 서명이 없는 블록은 넣지 않는다 (서명은 호출하는 쪽이 붙이거나 확인한다) */
    public Append append(BlockProposalDto b) {
        if (!wellFormed(b) || b.signerId() == null || b.signature() == null) {
            return Append.INVALID;
        }
        synchronized (lock) {
            Optional<Block> existing = repo.findById(b.blockIndex());
            if (existing.isPresent()) {
                return sameBlock(existing.get(), b) ? Append.ALREADY_HAVE : Append.CONFLICT;
            }
            Head head = head();
            if (b.blockIndex() > head.index() + 1) {
                return Append.GAP;
            }
            if (b.blockIndex() != head.index() + 1 || !head.hash().equals(b.prevBlockHash())) {
                return Append.CONFLICT;
            }
            if (!chainIntact()) {
                return Append.BROKEN;
            }
            if (!versionIncreases(b)) {
                return Append.INVALID;
            }
            repo.saveAndFlush(toEntity(b));
            return Append.APPENDED;
        }
    }

    /** 같은 좌석 키의 마지막 버전보다 큰지 */
    public boolean versionIncreases(BlockProposalDto b) {
        return latestFor(b.recordKey()).map(x -> b.version() > x.version).orElse(true);
    }

    /** fromIndex번부터 끝까지 지운다 — 따라잡기에 쓴 블록이 확정 블록과 다를 때 (복구는 5단계) */
    public void truncateFrom(long fromIndex) {
        synchronized (lock) {
            repo.deleteByBlockIndexGreaterThanEqual(fromIndex);
            if (verifiedIndex >= fromIndex) {
                verifiedIndex = 0;
                verifiedHash = GENESIS;
            }
            signedAuditAt = 0;          // 다음 판정 검사는 처음부터 (auditSigned)
        }
    }

    // ------------------------------------------------------------------ 체인 무결성

    /** 처음부터 전체 검사 — 번호 · 연결 · 해시 */
    public boolean audit() {
        return audit(null);
    }

    /**
     * 처음부터 전체 검사 — 번호 · 연결 · 해시에 더해 블록마다 서명도 확인한다 (켤 때, 미정사항 12번).
     * 해시를 처음부터 다시 계산해 맞춰 놓은 조작은 해시 검사로는 못 잡고, 개인키가 없으면 서명을 다시 만들 수 없어 여기서 잡힌다.
     * 서명 확인은 해시보다 수십 배 느려서 조회마다 하지 않는다.
     *
     * @param signatureOk 블록 서명이 맞는지 (null이면 서명은 보지 않음)
     */
    public boolean audit(Predicate<BlockProposalDto> signatureOk) {
        ChainScan scan = scan(1, GENESIS, signatureOk);
        synchronized (lock) {
            chainBroken = !scan.valid();
            if (scan.valid()) {
                verifiedIndex = scan.lastIndex();
                verifiedHash = scan.lastHash();
            }
        }
        return scan.valid();
    }

    /**
     * 판정용 자기 검사 (해시 연결 + 블록마다 서명). 체인 전체는 fullEveryMillis에 한 번만 다시 읽고, 그 사이에는
     * 지난번에 서명까지 확인한 마지막 블록이 그대로인지 본 뒤 그 뒤에 붙은 블록만 검사한다.
     * 신고가 잇따라 와도 신고마다 장부 전체의 서명을 다시 확인하지 않게 한다 (블록 1만 개에서 한 번에 25~34초 걸렸다).
     * 오래된 블록의 조작은 전체 검사 · 켤 때 검사 · 순회 검사 · 원장 상태 조회가 잡는다.
     */
    public boolean auditSigned(Predicate<BlockProposalDto> signatureOk, long fullEveryMillis) {
        synchronized (signedLock) {
            long now = System.currentTimeMillis();
            boolean recent = signedAuditAt > 0 && now - signedAuditAt < fullEveryMillis;
            boolean junctionSame = signedIndex == 0
                    || repo.findById(signedIndex).map(b -> signedHash.equals(b.blockHash)).orElse(false);
            if (!recent || !junctionSame) {
                boolean ok = audit(signatureOk);
                if (ok) {
                    synchronized (lock) {
                        signedIndex = verifiedIndex;
                        signedHash = verifiedHash;
                    }
                    signedAuditAt = now;
                }
                return ok;
            }
            ChainScan scan = scan(signedIndex + 1, signedHash, signatureOk);
            if (!scan.valid()) {
                synchronized (lock) {
                    chainBroken = true;
                }
                return false;
            }
            signedIndex = scan.lastIndex();
            signedHash = scan.lastHash();
            return !chainBroken;
        }
    }

    /**
     * 조회용 검사: 이 블록 하나가 저장된 그대로 맞는지 — 블록 해시를 다시 계산하고, 앞 블록 · 뒤 블록과 이어지는지 본다.
     * 조회마다 체인 전체를 다시 읽지 않는다. 그렇게 하면 장부가 길수록 조회가 느려지고, 검증 요청이 몰릴 때 DB 연결을 다 쓴다
     * (2026-11-25 부하 테스트). 체인 전체는 켤 때(서명 포함) · 원장 상태 조회 · 순회 검사가 보고, 블록을 붙일 때는 새로 붙는
     * 구간을 본다 — etcd도 무결성 검사를 켤 때와 주기적으로 하고, 요청마다 하지 않는다.
     * 어긋난 것이 보이면 전체 검사로 확정한다. 깨졌으면 chainBroken이 서고, 이 노드는 다음 블록을 붙이지 못해 리더 층에서 빠진다.
     */
    public boolean blockIntact(Block b) {
        if (b == null || b.blockIndex == null || b.blockIndex < 1 || chainBroken) {
            return false;
        }
        if (linked(b)) {
            return true;
        }
        // 드문 경우: 정말 깨졌거나, 복구가 장부를 바꾸는 사이에 읽었다. 전체를 다시 읽어 판정하고, 정상이면 그 블록을 새로 읽어 본다
        return audit() && repo.findById(b.blockIndex).filter(x -> sameBlock(x, toProposal(b))).filter(this::linked).isPresent();
    }

    /** 블록 해시가 내용과 맞고, 앞 블록의 해시 · 뒤 블록의 prevBlockHash와 이어지는지. 뒤 블록이 없으면 마지막 블록이어야 한다 */
    private boolean linked(Block b) {
        if (!wellFormed(toProposal(b))) {
            return false;
        }
        String previous = b.blockIndex == 1 ? GENESIS : repo.findById(b.blockIndex - 1).map(x -> x.blockHash).orElse(null);
        if (!b.prevBlockHash.equals(previous)) {
            return false;
        }
        // 뒤 블록이 없는데 마지막 번호가 더 뒤라면 중간이 빠진 것이다 (+1은 방금 붙은 블록 — 읽는 사이에 붙을 수 있다)
        return repo.findById(b.blockIndex + 1).map(next -> b.blockHash.equals(next.prevBlockHash))
                .orElseGet(() -> head().index() <= b.blockIndex + 1);
    }

    /** 블록을 붙이기 전 검사. 이미 확인한 구간 뒤쪽만 읽는다. lock을 잡고 호출 */
    private boolean chainIntact() {
        if (chainBroken) {
            return false;
        }
        ChainScan scan = scan(verifiedIndex + 1, verifiedHash, null);
        if (!scan.valid()) {
            chainBroken = true;
            return false;
        }
        verifiedIndex = scan.lastIndex();
        verifiedHash = scan.lastHash();
        return true;
    }

    private record ChainScan(boolean valid, long lastIndex, String lastHash) {}

    /** from번 블록부터 끝까지 번호 · 이전 해시 연결 · 블록 해시를 다시 계산해 확인 (signatureOk가 있으면 서명도) */
    private ChainScan scan(long from, String expectedPrevious, Predicate<BlockProposalDto> signatureOk) {
        long index = from;
        String previous = expectedPrevious;
        while (true) {
            List<Block> page = repo.findByBlockIndexGreaterThanEqualOrderByBlockIndexAsc(index, PageRequest.of(0, PAGE));
            for (Block b : page) {
                if (b.blockIndex == null || b.blockIndex != index || b.timestamp == null
                        || !previous.equals(b.prevBlockHash) || !wellFormed(toProposal(b))
                        || (signatureOk != null && !signatureOk.test(toProposal(b)))) {
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

    // ------------------------------------------------------------------ 블록 도구

    /**
     * 블록 해시에는 시각을 넣지 않는다 (같은 기록을 다시 제안해도 같은 블록이 되게). 계산식은 바꾸지 않는다:
     * SHA-256(index ‖ prevHash ‖ recordKey ‖ version ‖ recordHash)
     */
    public String hash(BlockProposalDto p) {
        return hasher.hash(p.blockIndex().toString(), p.prevBlockHash(), p.recordKey(), p.version().toString(),
                p.recordHash());
    }

    /** 형식 · 해시가 맞는 블록인지 (앞 블록과의 연결은 보지 않는다) */
    public boolean wellFormed(BlockProposalDto p) {
        return p != null && p.blockIndex() != null && p.blockIndex() >= 1 && p.timestamp() != null
                && p.prevBlockHash() != null && HEX.matcher(p.prevBlockHash()).matches()
                && validRecord(p.recordKey(), p.recordHash(), p.version())
                && p.blockHash() != null && hash(p).equals(p.blockHash());
    }

    public static boolean validRecord(String key, String hash, Long version) {
        return key != null && HEX.matcher(key).matches() && hash != null && HEX.matcher(hash).matches()
                && version != null && version >= 1;
    }

    public BlockProposalDto build(long index, String prevHash, String recordKey, long version, String recordHash) {
        // 초 단위로 자른다 — DB 시각 정밀도에 따라 값이 달라지지 않게
        long time = System.currentTimeMillis() / 1000 * 1000;
        BlockProposalDto unsigned = new BlockProposalDto(index, prevHash, recordKey, version, recordHash, time, "");
        return new BlockProposalDto(index, prevHash, recordKey, version, recordHash, time, hash(unsigned));
    }

    public static boolean sameBlock(Block b, BlockProposalDto p) {
        return Objects.equals(p.blockHash(), b.blockHash)
                && Objects.equals(p.recordKey(), b.recordKey)
                && Objects.equals(p.version(), b.version)
                && Objects.equals(p.recordHash(), b.recordHash)
                && Objects.equals(p.prevBlockHash(), b.prevBlockHash);
    }

    /** 저장된 블록 그대로 (서명 포함) */
    public static BlockProposalDto toProposal(Block b) {
        return new BlockProposalDto(b.blockIndex, b.prevBlockHash, b.recordKey, b.version, b.recordHash,
                b.timestamp == null ? null : b.timestamp.toInstant(ZoneOffset.UTC).toEpochMilli(), b.blockHash,
                b.signerId, b.signature);
    }

    private static Block toEntity(BlockProposalDto p) {
        return new Block(p.blockIndex(), p.prevBlockHash(), p.recordKey(), p.version(), p.recordHash(),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(p.timestamp()), ZoneOffset.UTC), p.blockHash(),
                p.signerId(), p.signature());
    }
}
