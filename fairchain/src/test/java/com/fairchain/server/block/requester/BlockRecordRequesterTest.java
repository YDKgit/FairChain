package com.fairchain.server.block.requester;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockRecordRequesterTest {

    private static BlockRecordDto record(int n) {
        return new BlockRecordDto(String.format("%064x", n), 1L, String.format("%064x", n + 1000));
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 노드 공통: 쓰지 않는 메서드 */
    private abstract static class Node implements HttpRequester {
        @Override
        public void delete(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void subscribe(String path, Class<T> eventType, Consumer<T> onEvent) {
            throw new UnsupportedOperationException();
        }
    }

    /** 정해 둔 답만 돌려주는 가짜 노드 (빈 체인). answer가 null이면 응답 없음 */
    private static class ScriptedNode extends Node {
        private final BlockResultDto answer;
        private final long delayMs;
        final AtomicInteger calls = new AtomicInteger();

        ScriptedNode(BlockResultDto answer, long delayMs) {
            this.answer = answer;
            this.delayMs = delayMs;
        }

        @Override
        public <T> T post(String path, Object body, Class<T> type) {
            calls.incrementAndGet();
            pause(delayMs);
            if (answer == null) {
                throw new IllegalStateException("노드 꺼짐");
            }
            return type.cast(answer);
        }

        @Override
        public <T> T get(String path, Class<T> type) {
            if (answer == null) {
                throw new IllegalStateException("노드 꺼짐");
            }
            return type.cast(new NodeLedgerStatusDto(0L, "0".repeat(64), true, List.of()));
        }
    }

    /** 진짜 노드처럼 받은 기록을 자기 체인 끝에 쌓는 가짜 노드. 같은 기록은 같은 블록 번호 (재전송 안전) */
    private static class SimNode extends Node {
        final List<BlockRecordDto> chain = new ArrayList<>();
        volatile boolean down;

        @Override
        public synchronized <T> T post(String path, Object body, Class<T> type) {
            if (down) {
                throw new IllegalStateException("노드 꺼짐");
            }
            BlockRecordDto r = (BlockRecordDto) body;
            int i = chain.indexOf(r);
            if (i < 0) {
                chain.add(r);
                i = chain.size() - 1;
            }
            return type.cast(new BlockResultDto(true, (long) i + 1, hashAt(i)));
        }

        @Override
        public synchronized <T> T get(String path, Class<T> type) {
            if (down) {
                throw new IllegalStateException("노드 꺼짐");
            }
            String head = chain.isEmpty() ? "0".repeat(64) : hashAt(chain.size() - 1);
            return type.cast(new NodeLedgerStatusDto((long) chain.size(), head, true, List.of()));
        }

        /** 앞 블록들까지 같아야 같은 해시 (진짜 체인처럼) */
        private String hashAt(int i) {
            return Integer.toHexString(List.copyOf(chain.subList(0, i + 1)).hashCode());
        }
    }

    /** FairChain 기록 사본처럼 확정된 기록을 순서대로 모아 둔다 */
    private static class FakeLedger implements ConfirmedLedger {
        final List<BlockRecordDto> confirmed = new ArrayList<>();

        @Override
        public long height() {
            return confirmed.size();
        }

        @Override
        public List<ConfirmedBlock> between(long from, long to) {
            List<ConfirmedBlock> out = new ArrayList<>();
            for (long i = from; i <= Math.min(to, confirmed.size()); i++) {
                out.add(new ConfirmedBlock(i, confirmed.get((int) i - 1)));
            }
            return out;
        }
    }

    /** BlockRecordService처럼: 보내고, 다수가 승인하면 사본에 확정 */
    private static BlockResultDto recordAndConfirm(BlockRecordRequester requester, FakeLedger ledger, BlockRecordDto dto) {
        BlockResultDto result = requester.submitRecord(dto);
        if (result.accepted()) {
            assertEquals(ledger.confirmed.size() + 1L, result.blockIndex());
            ledger.confirmed.add(dto);
        }
        return result;
    }

    private static BlockResultDto submit(HttpRequester... nodes) {
        return new BlockRecordRequester(List.of(nodes), new FakeLedger()).submitRecord(record(1));
    }

    private static ScriptedNode accepted(long blockIndex) {
        return new ScriptedNode(new BlockResultDto(true, blockIndex, "h" + blockIndex), 0);
    }

    private static ScriptedNode rejected() {
        return new ScriptedNode(new BlockResultDto(false, null, null), 0);
    }

    private static ScriptedNode down() {
        return new ScriptedNode(null, 0);
    }

    // ------------------------------------------------------------------ 다수결

    @Test
    void 같은_기록을_노드_3대_모두에_보낸다() {
        ScriptedNode first = accepted(7), second = accepted(7), third = accepted(7);

        BlockResultDto result = submit(first, second, third);

        assertTrue(result.accepted());
        assertEquals(7L, result.blockIndex());
        assertEquals(1, first.calls.get());
        assertEquals(1, second.calls.get());
        assertEquals(1, third.calls.get());
    }

    @Test
    void 노드_3대에_차례로가_아니라_동시에_보낸다() {
        BlockResultDto answer = new BlockResultDto(true, 1L, "h1");
        long start = System.nanoTime();
        BlockResultDto result = submit(new ScriptedNode(answer, 300), new ScriptedNode(answer, 300), new ScriptedNode(answer, 300));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.accepted());
        assertTrue(elapsedMs < 800, "차례로 보내면 900ms 이상 걸린다: " + elapsedMs + "ms");
    }

    @Test
    void 노드_1대가_꺼져_있어도_2대가_승인하면_확정() {
        BlockResultDto result = submit(accepted(3), down(), accepted(3));

        assertTrue(result.accepted());
        assertEquals(3L, result.blockIndex());
    }

    @Test
    void 노드_1대가_거절해도_2대가_승인하면_확정() {
        assertTrue(submit(rejected(), accepted(3), accepted(3)).accepted());
    }

    @Test
    void 승인이_1대뿐이면_미승인() {
        assertFalse(submit(accepted(3), down(), down()).accepted());
        assertFalse(submit(accepted(3), rejected(), down()).accepted());
    }

    @Test
    void 승인해도_블록_번호가_서로_다르면_미승인() {
        assertFalse(submit(accepted(3), accepted(4), down()).accepted());
    }

    @Test
    void 번호가_같아도_블록_해시가_다르면_미승인() {
        ScriptedNode a = new ScriptedNode(new BlockResultDto(true, 3L, "x"), 0);
        ScriptedNode b = new ScriptedNode(new BlockResultDto(true, 3L, "y"), 0);
        assertFalse(submit(a, b, down()).accepted(), "체인 내용이 다른 두 노드는 같은 블록이 아니다");
    }

    @Test
    void 뒤처진_노드_1대의_다른_답은_무시된다() {
        BlockResultDto result = submit(accepted(5), accepted(5), accepted(2));

        assertTrue(result.accepted());
        assertEquals(5L, result.blockIndex());
    }

    @Test
    void 과반이_거절하면_미승인() {
        assertFalse(submit(rejected(), rejected(), accepted(3)).accepted());
    }

    // ------------------------------------------------------------------ 따라잡기

    @Test
    void 꺼졌다_켜진_노드는_빠진_기록을_확정_순서대로_다시_받는다() {
        SimNode a = new SimNode(), b = new SimNode(), c = new SimNode();
        FakeLedger ledger = new FakeLedger();
        BlockRecordRequester requester = new BlockRecordRequester(List.of(a, b, c), ledger);

        recordAndConfirm(requester, ledger, record(1));
        c.down = true;
        assertTrue(recordAndConfirm(requester, ledger, record(2)).accepted(), "2대로 확정");
        assertTrue(recordAndConfirm(requester, ledger, record(3)).accepted());
        assertEquals(1, c.chain.size());

        c.down = false;
        requester.catchUpNodes();                       // 주기 점검: 새 기록 없이도 따라잡는다
        assertEquals(a.chain, c.chain);

        assertTrue(recordAndConfirm(requester, ledger, record(4)).accepted());
        assertEquals(a.chain, b.chain);
        assertEquals(a.chain, c.chain);
        assertEquals(4, c.chain.size());
    }

    @Test
    void 장부를_지우고_다시_켠_노드는_처음부터_다시_받는다() {
        SimNode a = new SimNode(), b = new SimNode(), c = new SimNode();
        FakeLedger ledger = new FakeLedger();
        BlockRecordRequester requester = new BlockRecordRequester(List.of(a, b, c), ledger);
        for (int i = 1; i <= 5; i++) {
            recordAndConfirm(requester, ledger, record(i));
        }

        c.chain.clear();
        requester.catchUpNodes();

        assertEquals(a.chain, c.chain);
    }

    @Test
    void 체인이_갈라진_노드는_투표에서_빠지고_새_기록도_받지_않는다() {
        SimNode a = new SimNode(), b = new SimNode(), c = new SimNode();
        FakeLedger ledger = new FakeLedger();
        BlockRecordRequester requester = new BlockRecordRequester(List.of(a, b, c), ledger);
        c.down = true;
        recordAndConfirm(requester, ledger, record(1));
        c.down = false;
        c.chain.add(record(99));                        // 같은 높이 1인데 다른 블록

        requester.catchUpNodes();
        assertTrue(recordAndConfirm(requester, ledger, record(2)).accepted(), "정상인 2대로 확정");

        assertEquals(List.of(record(99)), c.chain, "갈라진 노드에는 새 기록을 보내지 않는다");
        assertEquals(a.chain, b.chain);
    }
}
