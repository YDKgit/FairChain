package com.fairchain.node.service;

import com.fairchain.node.config.NodeSettings;
import com.fairchain.node.entity.Block;
import com.fairchain.node.ledger.Ledger;
import com.fairchain.node.raft.MemberRecord;
import com.fairchain.node.raft.MemberRegistry;
import com.fairchain.node.raft.RaftNodeHolder;
import com.fairchain.node.security.NodeKeys;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 순회 검사 (미정사항 12번, 옵션 · 기본 꺼짐): 1초에 블록 N개씩 앞에서부터 해시 연결 · 해시 재계산 · 서명을 확인하고,
 * 끝나면 처음으로 돌아간다. 전체를 한 번에 검사하지 않으므로 느려지지 않는다. 시연 설정에서만 켠다 ("DB를 고치면 몇 초 안에 잡힘").
 * 깨진 블록을 찾으면 체인 손상으로 빠지고, 신고 → 판정 → #k부터 복구로 이어진다.
 */
@Service
public class PatrolService {

    private static final Logger log = LoggerFactory.getLogger(PatrolService.class);

    private final Ledger ledger;
    private final RaftNodeHolder raft;
    private final MemberRegistry registry;
    private final NodeSettings settings;
    private final NodeKeys keys;
    private final boolean enabled;
    private final int blocksPerSecond;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "patrol");
        t.setDaemon(true);
        return t;
    });
    private long next = 1;
    private String previousHash = Ledger.GENESIS;

    public PatrolService(Ledger ledger, RaftNodeHolder raft, MemberRegistry registry, NodeSettings settings, NodeKeys keys,
                         @Value("${node.patrol.enabled:false}") boolean enabled,
                         @Value("${node.patrol.blocks-per-sec:50}") int blocksPerSecond) {
        this.ledger = ledger;
        this.raft = raft;
        this.registry = registry;
        this.settings = settings;
        this.keys = keys;
        this.enabled = enabled;
        this.blocksPerSecond = Math.max(1, blocksPerSecond);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (enabled) {
            log.info("순회 검사 켜짐: 1초에 블록 {}개", blocksPerSecond);
            scheduler.scheduleWithFixedDelay(this::step, 1000, 1000, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    private void step() {
        try {
            if (raft.outReason() != null) {
                next = 1;
                previousHash = Ledger.GENESIS;
                return;
            }
            long head = ledger.head().index();
            if (next > head) {
                next = 1;
                previousHash = Ledger.GENESIS;
            }
            long end = Math.min(head, next + blocksPerSecond - 1);
            for (long i = next; i <= end; i++) {
                Optional<Block> b = ledger.block(i);
                if (b.isEmpty()) {
                    return;     // 복구 등으로 장부가 바뀌는 중 — 다음에 다시
                }
                BlockProposalDto p = Ledger.toProposal(b.get());
                if (!previousHash.equals(p.prevBlockHash()) || !ledger.wellFormed(p) || !signatureOk(p)) {
                    raft.chainInvalid("순회 검사에서 블록 #" + i + "가 맞지 않음 (해시 연결 · 재계산 · 서명)");
                    return;
                }
                previousHash = p.blockHash();
                next = i + 1;
            }
        } catch (RuntimeException e) {
            log.debug("순회 검사 실패: {}", e.toString());
        }
    }

    /** 리더 층 노드: 내 서명. 팀원: 리더 층 노드의 서명 */
    private boolean signatureOk(BlockProposalDto p) {
        if (settings.leaderLayer()) {
            return keys.signedByMe(p);
        }
        String key = p.signerId() == null ? null : registry.get(p.signerId()).map(MemberRecord::publicKey).orElse(null);
        return key == null || NodeKeys.verify(key, p);      // 공개키를 아직 모르면 서명은 건너뛴다 (해시는 본다)
    }
}
