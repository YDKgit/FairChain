package com.fairchain.node.ledger;

import com.fairchain.node.entity.Block;
import com.fairchain.node.repository.BlockRepository;
import jakarta.persistence.EntityManager;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 장부가 길어져도 느려지지 않는지 (2026-11-26: 블록 1만 개에서 기록 한 건이 25ms → 430ms).
 * 원인은 "마지막 블록"을 ORDER BY … DESC LIMIT 1로 찾은 것 — H2는 이것을 장부 전체를 읽어 정렬한다.
 */
@DataJpaTest
class LedgerScaleTest {

    private static final String H = "a".repeat(64);

    @Autowired BlockRepository repo;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    /** 형식만 맞춘 블록 n개를 바로 넣는다 (해시 연결은 보지 않는 시험) */
    private void fill(int n) {
        List<Object[]> rows = new ArrayList<>();
        Timestamp now = new Timestamp(System.currentTimeMillis());
        for (int i = 1; i <= n; i++) {
            rows.add(new Object[]{(long) i, H, String.format("%064x", i), 1L, H, now, String.format("%064x", i)});
        }
        jdbc.batchUpdate("insert into block (block_index, prev_block_hash, record_key, version, record_hash, timestamp, block_hash) "
                + "values (?, ?, ?, ?, ?, ?, ?)", rows);
    }

    @Test
    void theLastBlockIsFoundThroughTheIndexNotByReadingTheWholeLedger() {
        // 노드와 같은 설정 (application.yml): 같은 조회의 앞 결과를 다시 쓰지 않는다. 켜 두면 아래 20번이 캐시로 끝나 비교가 안 된다
        jdbc.execute("SET OPTIMIZE_REUSE_RESULTS 0");
        fill(20_000);
        Ledger ledger = new Ledger(repo);
        assertEquals(20_000, ledger.head().index());

        String plan = jdbc.queryForObject("explain select max(block_index) from block", String.class);
        assertTrue(plan.contains("direct lookup"), "MAX는 인덱스 끝에서 바로 읽는다: " + plan);

        // 예전 방식 그대로: Spring Data의 findTopByOrderByBlockIndexDesc = JPQL + 첫 1건
        Runnable oldHead = () -> em.createQuery("select b from Block b order by b.blockIndex desc", Block.class)
                .setMaxResults(1).getResultList();
        long oldWay = timeMillis(oldHead);
        long newWay = timeMillis(ledger::head);
        System.out.printf("[측정] 블록 2만 개에서 마지막 블록 찾기 20번: 예전 방식 %d ms · 지금 %d ms%n", oldWay, newWay);
        assertTrue(newWay < oldWay, "예전 " + oldWay + " ms, 지금 " + newWay + " ms");
    }

    private static long timeMillis(Runnable r) {
        r.run();     // 처음 한 번은 준비 (쿼리 계획 · 클래스 로딩)
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            r.run();
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /**
     * 신고 판정 전 자기 검사: 1분 안에는 새로 붙은 블록만 서명까지 보고, 그때가 지나면 처음부터.
     * (테스트는 한 트랜잭션이라 JPA가 읽어 둔 블록을 재사용한다 — JDBC로 고친 뒤 em.clear()로 비운다. 노드는 조회마다 새로 읽는다)
     */
    @Test
    void theSelfCheckBeforeAJudgementReadsOnlyNewBlocksWithinAMinute() {
        Ledger ledger = new Ledger(repo);
        for (int i = 1; i <= 5; i++) {
            append(ledger, i);
        }
        assertTrue(ledger.auditSigned(p -> true, 60_000), "처음에는 전체 검사");

        jdbc.update("update block set signature = 'bad' where block_index = 2");
        em.clear();
        assertTrue(ledger.auditSigned(p -> "sig".equals(p.signature()), 60_000),
                "1분 안에는 이미 확인한 앞 블록을 다시 읽지 않는다 (그 조작은 전체 검사 · 순회 검사 · 켤 때 검사가 잡는다)");
        assertFalse(ledger.auditSigned(p -> "sig".equals(p.signature()), 0), "전체 검사 때가 되면 잡는다");

        jdbc.update("update block set signature = 'sig' where block_index = 2");
        em.clear();
        assertTrue(ledger.audit(p -> true));
        assertTrue(ledger.auditSigned(p -> true, 60_000));
        append(ledger, 6);
        jdbc.update("update block set signature = 'bad' where block_index = 6");
        em.clear();
        assertFalse(ledger.auditSigned(p -> "sig".equals(p.signature()), 60_000), "새로 붙은 블록은 1분 안에도 서명까지 본다");
    }

    private static void append(Ledger ledger, long index) {
        Ledger.Head head = ledger.head();
        BlockProposalDto b = ledger.build(index, head.hash(), String.format("%064x", index), 1, H);
        BlockProposalDto signed = new BlockProposalDto(b.blockIndex(), b.prevBlockHash(), b.recordKey(), b.version(),
                b.recordHash(), b.timestamp(), b.blockHash(), "A1", "sig");
        assertEquals(Ledger.Append.APPENDED, ledger.append(signed));
    }
}
