package com.fairchain.example.queue;

import com.fairchain.api.dto.EnterQueueResponseDto;
import com.fairchain.api.dto.QueueStatusResponseDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 상태 확인을 FairChain이 알려 준 간격보다 일찍 하면 FairChain에 가지 않고 직전 답을 준다 */
class QueueServiceTest {

    /** FairChain 대신 — 물어본 횟수를 세고, 정해 둔 답을 차례로 준다 */
    static class FakeQueue implements QueueAccess {
        final List<QueueStatusResponseDto> answers = new ArrayList<>();
        int asked;

        @Override public EnterQueueResponseDto enter(Long memberId, Long eventId) { return new EnterQueueResponseDto(memberId, 1L); }
        @Override public void leave(Long memberId, Long eventId) { }
        @Override public void leaveAll(Long memberId) { }
        @Override public void subscribe(Long memberId, Long eventId, Consumer<QueueStatusResponseDto> onEvent, Runnable onClosed) { }

        @Override
        public QueueStatusResponseDto status(Long memberId, Long eventId) {
            return answers.get(Math.min(asked++, answers.size() - 1));
        }
    }

    private static QueueStatusResponseDto waiting(long rank, long nextPollMs) {
        return new QueueStatusResponseDto(QueueStatusResponseDto.WAITING, rank, 9000L, nextPollMs);
    }

    @Test
    void askingTooEarlyGetsTheLastAnswerWithoutCallingFairChain() {
        FakeQueue fair = new FakeQueue();
        fair.answers.add(waiting(5000, 10_000));
        fair.answers.add(waiting(4000, 10_000));
        AtomicLong now = new AtomicLong(1_000_000);
        QueueService service = new QueueService(fair, null, now::get);

        QueueStatusDto first = service.getStatus(7L, 2L);
        assertEquals(5000L, first.rank());
        assertEquals(10_000L, first.nextPollMs());

        now.addAndGet(1000);                                  // 1초 뒤 (간격 10초를 지키지 않음)
        QueueStatusDto early = service.getStatus(7L, 2L);
        assertEquals(1, fair.asked, "FairChain에 다시 묻지 않는다");
        assertEquals(5000L, early.rank());
        assertEquals(9000L, early.nextPollMs(), "남은 시간만 알려 준다");

        now.addAndGet(9000);                                  // 간격을 지켜 물으면 새 답
        QueueStatusDto onTime = service.getStatus(7L, 2L);
        assertEquals(2, fair.asked);
        assertEquals(4000L, onTime.rank());
    }

    @Test
    void anAdmittedAnswerIsNeverHeldBack() {
        FakeQueue fair = new FakeQueue();
        fair.answers.add(waiting(150, 1000));
        fair.answers.add(new QueueStatusResponseDto(QueueStatusResponseDto.ADMITTED, null, null));
        AtomicLong now = new AtomicLong(1_000_000);
        QueueService service = new QueueService(fair, null, now::get);

        service.getStatus(7L, 2L);
        now.addAndGet(1000);
        QueueStatusDto admitted = service.getStatus(7L, 2L);
        assertEquals(QueueStatusResponseDto.ADMITTED, admitted.state());
        assertNull(admitted.nextPollMs());
        now.addAndGet(100);
        service.getStatus(7L, 2L);
        assertEquals(3, fair.asked, "입장 허용 뒤에는 기억하지 않고 매번 묻는다");
    }

    @Test
    void membersAndConcertsAreKeptApart() {
        FakeQueue fair = new FakeQueue();
        fair.answers.add(waiting(5000, 10_000));
        AtomicLong now = new AtomicLong(1_000_000);
        QueueService service = new QueueService(fair, null, now::get);

        service.getStatus(7L, 2L);
        service.getStatus(8L, 2L);
        service.getStatus(7L, 3L);
        assertEquals(3, fair.asked, "다른 회원 · 다른 공연은 각자 묻는다");
        service.leaveQueue(7L, 2L);
        service.getStatus(7L, 2L);
        assertEquals(4, fair.asked, "줄에서 나가면 기억을 지운다");
    }
}
