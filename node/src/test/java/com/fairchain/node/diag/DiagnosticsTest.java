package com.fairchain.node.diag;

import org.junit.jupiter.api.Test;

import java.lang.management.ThreadInfo;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 상태 로그 · 멈춤 의심 때 쓰는 도구: 요청 집계와 스레드 덤프 */
class DiagnosticsTest {

    @Test
    void requestsAreCountedByKindAndTheWindowStartsOverAfterDraining() {
        RequestStats stats = new RequestStats();
        stats.record(RequestStats.kind("POST", "/node/records"), 12, false);
        stats.record(RequestStats.kind("POST", "/node/records/verify-batch"), 40, false);
        stats.record(RequestStats.kind("POST", "/node/records/verify"), 7, true);
        stats.record(RequestStats.kind("GET", "/node/records/proof/abc"), 3, false);
        stats.record(RequestStats.kind("POST", "/node/raft/messages"), 1, false);
        stats.record(RequestStats.kind("GET", "/node/admin/status"), 2, false);
        stats.record(RequestStats.kind("GET", "/node/consensus/blocks"), 5, false);

        RequestStats.Window w = stats.drain();
        assertEquals(1, w.count()[RequestStats.Kind.WRITE.ordinal()]);
        assertEquals(3, w.count()[RequestStats.Kind.VERIFY.ordinal()]);
        assertEquals(1, w.count()[RequestStats.Kind.RAFT.ordinal()]);
        assertEquals(1, w.count()[RequestStats.Kind.STATUS.ordinal()]);
        assertEquals(1, w.count()[RequestStats.Kind.OTHER.ordinal()]);
        assertEquals(7, w.total());
        assertEquals(1, w.totalFailed());
        assertEquals(RequestStats.Kind.VERIFY, w.slowest());
        assertEquals(40, w.maxMillis()[RequestStats.Kind.VERIFY.ordinal()]);

        assertEquals(0, stats.drain().total(), "꺼낸 뒤에는 0부터 다시 센다");
        assertEquals(0, stats.oldestInFlightMillis());
    }

    /** 한 스레드가 잠금을 쥐고, 다른 스레드가 그 잠금을 기다리는 모습이 jstack처럼 보여야 교착을 읽을 수 있다 */
    @Test
    void theDumpShowsWhoHoldsALockAndWhoWaitsForIt() throws Exception {
        Object lock = new Object();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "test-holder");
        Thread waiter = new Thread(() -> {
            synchronized (lock) {
                lock.hashCode();
            }
        }, "test-waiter");
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));
        waiter.start();
        long until = System.currentTimeMillis() + 5000;
        while (waiter.getState() != Thread.State.BLOCKED && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        try {
            ThreadInfo[] threads = ThreadDumps.take();
            String text = ThreadDumps.format(threads);
            String id = String.format("<0x%08x>", System.identityHashCode(lock));
            String holderPart = text.substring(text.indexOf("\"test-holder\""));
            String waiterPart = text.substring(text.indexOf("\"test-waiter\""));
            assertTrue(holderPart.substring(0, holderPart.indexOf("\n\n")).contains("- locked " + id), holderPart);
            assertTrue(waiterPart.substring(0, waiterPart.indexOf("\n\n")).contains("- waiting to lock " + id), waiterPart);
            assertTrue(waiterPart.contains("BLOCKED"));
            assertTrue(ThreadDumps.count(threads).blocked() >= 1);
        } finally {
            release.countDown();
            holder.join(5000);
            waiter.join(5000);
        }
    }
}
