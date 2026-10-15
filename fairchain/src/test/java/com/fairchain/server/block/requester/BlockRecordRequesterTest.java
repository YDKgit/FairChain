package com.fairchain.server.block.requester;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockRecordRequesterTest {

    private static final BlockRecordDto RECORD = new BlockRecordDto("a".repeat(64), 1L, "b".repeat(64));

    /** 정해 둔 답을 돌려주는 가짜 노드. answer가 null이면 응답 없음(예외) */
    private static class FakeNode implements HttpRequester {
        private final BlockResultDto answer;
        final AtomicInteger calls = new AtomicInteger();

        FakeNode(BlockResultDto answer) {
            this.answer = answer;
        }

        @Override
        public <T> T post(String path, Object body, Class<T> responseType) {
            calls.incrementAndGet();
            if (answer == null) {
                throw new IllegalStateException("노드 꺼짐");
            }
            return responseType.cast(answer);
        }

        @Override
        public <T> T get(String path, Class<T> responseType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void subscribe(String path, Class<T> eventType, Consumer<T> onEvent) {
            throw new UnsupportedOperationException();
        }
    }

    private static FakeNode accepted(long blockIndex) {
        return new FakeNode(new BlockResultDto(true, blockIndex));
    }

    private static FakeNode rejected() {
        return new FakeNode(new BlockResultDto(false, null));
    }

    private static FakeNode down() {
        return new FakeNode(null);
    }

    private static BlockResultDto submit(FakeNode... nodes) {
        return new BlockRecordRequester(List.of(nodes)).submitRecord(RECORD);
    }

    @Test
    void 같은_기록을_노드_3대_모두에_보낸다() {
        FakeNode first = accepted(7);
        FakeNode second = accepted(7);
        FakeNode third = accepted(7);

        BlockResultDto result = submit(first, second, third);

        assertTrue(result.accepted());
        assertEquals(7L, result.blockIndex());
        assertEquals(1, first.calls.get());
        assertEquals(1, second.calls.get());
        assertEquals(1, third.calls.get());
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
        // 체인 길이가 어긋난 노드들 — 같은 결과가 2대 이상이어야 한다
        assertFalse(submit(accepted(3), accepted(4), down()).accepted());
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
}
