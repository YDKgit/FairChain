package com.fairchain.node.raft;

import com.fairchain.node.entity.Block;
import com.fairchain.node.ledger.Ledger;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.ReasonCode;
import com.fairchain.nodeapi.dto.VerdictDto;
import io.microraft.statemachine.StateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Raft 로그에서 확정된 항목을 순서대로 실행한다 (MicroRaft {@link StateMachine}). 모든 리더 층 노드가 같은 순서로 같은 일을 한다.
 *
 * <h3>블록 검사는 "받을 때"가 아니라 "적용할 때" (계획 4.4)</h3>
 * Raft에서 팔로워는 로그 항목을 내용 때문에 거부할 수 없다 (거부하면 로그 일치 규칙이 깨진다). Fabric도 블록 내용은
 * 피어가 장부에 쓸 때 검사한다. 그래서 여기서 검사한다: 블록이 검사를 통과하기 전에는 H2에 들어가지 않는다.
 *
 * <h3>서명 (Fabric 방식, 계획 4.5)</h3>
 * 로그의 블록에는 서명이 없다 — 과반이 같은 로그를 저장한 것이 곧 합의다. 리더 층 노드마다 확정 블록을 자기 H2에 쓸 때 자기 키로
 * blockHash에 서명해 블록 옆 칸에 둔다. 서명 확인은 로그 밖에서 들어오는 블록(따라잡기 · 팀원 · 복구)과 켤 때 전체 검사에서 한다.
 *
 * <h3>두 개의 "마지막 블록"</h3>
 * <ul>
 *   <li>{@code logHead} — Raft 로그로만 정해진 마지막 블록 (모든 정상 노드에서 같다)</li>
 *   <li>{@link Ledger#head()} — 이 노드 H2에 실제로 있는 마지막 블록</li>
 * </ul>
 * 둘이 어긋나는 경우는 셋뿐이다: H2가 뒤처짐(새 노드 · 스냅샷 → 따라잡기), H2가 앞섬(재시작 후 로그를 다시 적용하는 중 →
 * 같은 블록인지만 확인), H2가 다름(누가 H2를 고침 → CHAIN_INVALID, 이 노드는 스스로 빠진다).
 */
public class BlockStateMachine implements StateMachine {

    private static final Logger log = LoggerFactory.getLogger(BlockStateMachine.class);

    /** 노드 쪽에서 처리할 일 ({@link RaftNodeHolder}) */
    public interface Listener {
        /** 내 H2가 Raft 로그와 다르다 — 스스로 빠진다 */
        void chainInvalid(String why);

        /** 내 H2가 로그보다 뒤처졌다 — 다른 노드에서 블록을 받아 온다 */
        void catchUpNeeded();

        /** 제외가 로그에 기록됐다 (verdict에 갈라진 번호 #k와 증거) */
        void excluded(String nodeId, long logIndex, VerdictDto verdict);

        /** 복귀가 로그에 기록됐다 */
        void restored(String nodeId, long logIndex);

        /** 해지가 로그에 기록됐다 */
        default void removed(String nodeId, long logIndex) {
        }

        /** 스냅샷으로 명단을 통째로 받았다 — 그 안에 내 제외가 들어 있을 수 있다 */
        default void snapshotInstalled(long logIndex) {
        }

        /** 확정 블록을 내 H2에 썼다 — 팀 리더는 팀원에게 보낸다 */
        default void blockStored(long index) {
        }
    }

    private final Ledger ledger;
    private final MemberRegistry registry;
    private final Listener listener;
    /** 확정 블록에 이 노드의 서명을 붙인다 (Fabric 방식: 노드마다 자기 사본에 서명, 계획 4.5) */
    private final UnaryOperator<BlockProposalDto> signer;

    private volatile long logHeadIndex = 0;
    private volatile String logHeadHash = Ledger.GENESIS;

    public BlockStateMachine(Ledger ledger, MemberRegistry registry, Listener listener,
                             UnaryOperator<BlockProposalDto> signer) {
        this.ledger = ledger;
        this.registry = registry;
        this.listener = listener;
        this.signer = signer;
    }

    public Ledger.Head logHead() {
        return new Ledger.Head(logHeadIndex, logHeadHash);
    }

    @Override
    public Object runOperation(long commitIndex, Object operation) {
        if (operation instanceof LedgerQuery.Head) {
            return logHead();
        }
        if (!(operation instanceof LedgerOp op)) {
            throw new IllegalArgumentException("Unknown operation: " + operation);
        }
        if (op instanceof LedgerOp.AppendBlock a) {
            return append(a.block());
        }
        if (op instanceof LedgerOp.NewTerm) {
            return ApplyResult.OK;
        }
        if (op instanceof LedgerOp.RegisterMember m) {
            // 등록된 공개키는 바꾸지 않는다 — 키 교체는 "해지 후 새 키로 재가입" (미정사항 9번)
            String registered = registry.get(m.id()).map(MemberRecord::publicKey).orElse(null);
            if (registered != null && m.publicKey() != null && !registered.equals(m.publicKey())) {
                return ApplyResult.rejected(ReasonCode.INVALID_BLOCK);
            }
            registry.register(new MemberRecord(m.id(), m.team(), m.url(),
                    m.publicKey() != null ? m.publicKey() : registered));
            return ApplyResult.OK;
        }
        if (op instanceof LedgerOp.RemoveMember r) {
            registry.remove(r.id());
            listener.removed(r.id(), commitIndex);
            return ApplyResult.OK;
        }
        if (op instanceof LedgerOp.Exclude e) {
            registry.exclude(e.nodeId(), commitIndex);
            if (e.verdict() != null) {
                registry.addVerdict(e.verdict());
            }
            listener.excluded(e.nodeId(), commitIndex, e.verdict());
            return ApplyResult.OK;
        }
        if (op instanceof LedgerOp.Restore r) {
            registry.restore(r.nodeId(), commitIndex);
            registry.addVerdict(new VerdictDto(r.nodeId(), ReasonCode.OK, r.height(), null, null, List.of(),
                    "RESTORED", r.decidedBy(), r.decidedAt()));
            listener.restored(r.nodeId(), commitIndex);
            return ApplyResult.OK;
        }
        if (op instanceof LedgerOp.Verdict v) {
            registry.addVerdict(v.verdict());
            return ApplyResult.OK;
        }
        if (op instanceof LedgerOp.Halt h) {
            registry.setHalted(true);
            registry.addVerdict(h.verdict());
            log.error("기록 중단 — 서명이 둘 다 맞는 서로 다른 블록 #{} (관리자 확인 필요)", h.verdict().blockIndex());
            return ApplyResult.OK;
        }
        if (op instanceof LedgerOp.Resume r) {
            registry.setHalted(false);
            registry.addVerdict(new VerdictDto(null, ReasonCode.OK, null, null, null, List.of(), "RESUMED",
                    r.decidedBy(), r.decidedAt()));
            return ApplyResult.OK;
        }
        throw new IllegalArgumentException("Unhandled operation: " + op);
    }

    private ApplyResult append(BlockProposalDto b) {
        if (b == null || b.blockIndex() == null) {
            return ApplyResult.rejected(ReasonCode.INVALID_BLOCK);
        }
        long index = b.blockIndex();
        if (index <= logHeadIndex) {
            // 같은 번호 블록이 이미 확정됨: 리더가 시간 초과 뒤 같은 기록을 다시 넣은 경우면 같은 블록, 아니면 늦은 블록
            boolean same = index == logHeadIndex ? logHeadHash.equals(b.blockHash())
                    : ledger.block(index).map(x -> Ledger.sameBlock(x, b)).orElse(false);
            return same ? ApplyResult.block(index) : ApplyResult.rejected(ReasonCode.ALREADY_VOTED);
        }
        if (index != logHeadIndex + 1 || !logHeadHash.equals(b.prevBlockHash()) || !ledger.wellFormed(b)) {
            log.warn("이어지지 않거나 틀린 블록 #{} — 적용하지 않음", index);
            return ApplyResult.rejected(ReasonCode.INVALID_BLOCK);
        }

        Ledger.Head stored = ledger.head();
        if (stored.index() == logHeadIndex) {
            if (!stored.hash().equals(logHeadHash)) {
                advance(b);
                listener.chainInvalid("H2의 마지막 블록 #" + stored.index() + " 해시가 Raft 로그와 다름");
                return ApplyResult.block(index);
            }
            if (!ledger.versionIncreases(b)) {
                return ApplyResult.rejected(ReasonCode.INVALID_BLOCK);
            }
            advance(b);
            Ledger.Append result = ledger.append(signer.apply(b));
            if (result == Ledger.Append.APPENDED) {
                listener.blockStored(index);
            } else if (result != Ledger.Append.ALREADY_HAVE) {
                listener.chainInvalid("블록 #" + index + "를 H2에 붙이지 못함: " + result);
            }
            return ApplyResult.block(index);
        }

        advance(b);
        if (stored.index() > index - 1) {
            // 재시작 뒤 로그를 다시 적용하는 중 — H2에 이미 있는 블록이 같은지만 본다
            Optional<Block> have = ledger.block(index);
            if (have.isEmpty() || !Ledger.sameBlock(have.get(), b)) {
                listener.chainInvalid("H2의 블록 #" + index + "가 Raft 로그와 다름");
            }
        } else {
            listener.catchUpNeeded();
        }
        return ApplyResult.block(index);
    }

    private void advance(BlockProposalDto b) {
        logHeadHash = b.blockHash();
        logHeadIndex = b.blockIndex();
    }

    @Override
    public void takeSnapshot(long commitIndex, Consumer<Object> snapshotChunkConsumer) {
        snapshotChunkConsumer.accept(registry.snapshot(logHeadIndex, logHeadHash));
    }

    @Override
    public void installSnapshot(long commitIndex, List<Object> snapshotChunks) {
        if (snapshotChunks.isEmpty() || !(snapshotChunks.get(0) instanceof LedgerSnapshot s)) {
            throw new IllegalArgumentException("Unexpected snapshot: " + snapshotChunks);
        }
        registry.install(s);
        logHeadHash = s.lastBlockHash();
        logHeadIndex = s.lastBlockIndex();
        listener.snapshotInstalled(commitIndex);
        Ledger.Head stored = ledger.head();
        if (stored.index() >= s.lastBlockIndex()) {
            boolean same = s.lastBlockIndex() == 0
                    || ledger.block(s.lastBlockIndex()).map(x -> x.blockHash.equals(s.lastBlockHash())).orElse(false);
            if (!same) {
                listener.chainInvalid("H2의 블록 #" + s.lastBlockIndex() + "가 스냅샷과 다름");
            }
        } else {
            listener.catchUpNeeded();
        }
    }

    /** 새 리더의 임기 첫 항목 */
    @Override
    public Object getNewTermOperation() {
        return new LedgerOp.NewTerm();
    }
}
