package com.fairchain.node.diag;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;

/**
 * 프로세스 안에서 뜨는 스레드 덤프 (jstack · jcmd Thread.print와 같은 모양).
 * {@link ThreadInfo#toString()}은 스택을 8줄에서 자르므로 직접 쓴다. 누가 무엇을 쥐고(- locked) 무엇을 기다리는지
 * (- waiting to lock / - parking to wait for)가 보여야 교착을 읽을 수 있다.
 */
public final class ThreadDumps {

    private ThreadDumps() {
    }

    /** 덤프에서 바로 세어 본 값 — 로그 한 줄로 쓴다 */
    public record Counts(int threads, int waitingForConnection, int blocked, boolean ledgerWriterWaitsForConnection) {}

    public static ThreadInfo[] take() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        return mx.dumpAllThreads(mx.isObjectMonitorUsageSupported(), mx.isSynchronizerUsageSupported());
    }

    public static Counts count(ThreadInfo[] threads) {
        int waiting = 0;
        int blocked = 0;
        boolean writerWaits = false;
        for (ThreadInfo t : threads) {
            if (t == null) {
                continue;
            }
            boolean waitsForConnection = has(t, "com.zaxxer.hikari.pool.HikariPool", "getConnection");
            if (waitsForConnection) {
                waiting++;
                if (has(t, "com.fairchain.node.ledger.Ledger", "append")) {
                    writerWaits = true;      // 장부 잠금을 쥔 채 연결을 기다린다 — 2026-11-25 교착의 모양
                }
            }
            if (t.getThreadState() == Thread.State.BLOCKED) {
                blocked++;
            }
        }
        return new Counts(threads.length, waiting, blocked, writerWaits);
    }

    private static boolean has(ThreadInfo t, String className, String method) {
        return Arrays.stream(t.getStackTrace())
                .anyMatch(e -> className.equals(e.getClassName()) && method.equals(e.getMethodName()));
    }

    public static String format(ThreadInfo[] threads) {
        StringBuilder out = new StringBuilder();
        for (ThreadInfo t : threads) {
            if (t != null) {
                format(t, out);
            }
        }
        return out.toString();
    }

    static void format(ThreadInfo t, StringBuilder out) {
        out.append('"').append(t.getThreadName()).append("\" #").append(t.getThreadId());
        if (t.isDaemon()) {
            out.append(" daemon");
        }
        out.append(' ').append(t.getThreadState());
        if (t.getLockOwnerName() != null) {
            out.append(" (잠금 주인 \"").append(t.getLockOwnerName()).append("\" #").append(t.getLockOwnerId()).append(')');
        }
        out.append("\n   java.lang.Thread.State: ").append(t.getThreadState()).append('\n');
        StackTraceElement[] stack = t.getStackTrace();
        MonitorInfo[] monitors = t.getLockedMonitors();
        for (int depth = 0; depth < stack.length; depth++) {
            out.append("\tat ").append(stack[depth]).append('\n');
            if (depth == 0 && t.getLockInfo() != null) {
                String verb = switch (t.getThreadState()) {
                    case BLOCKED -> "waiting to lock";
                    case WAITING, TIMED_WAITING -> stack[0].getClassName().endsWith("Unsafe") ? "parking to wait for" : "waiting on";
                    default -> "waiting on";
                };
                out.append("\t- ").append(verb).append(' ').append(lock(t.getLockInfo())).append('\n');
            }
            for (MonitorInfo m : monitors) {
                if (m.getLockedStackDepth() == depth) {
                    out.append("\t- locked ").append(lock(m)).append('\n');
                }
            }
        }
        LockInfo[] synchronizers = t.getLockedSynchronizers();
        if (synchronizers.length > 0) {
            out.append("\n   Locked ownable synchronizers:\n");
            for (LockInfo s : synchronizers) {
                out.append("\t- ").append(lock(s)).append('\n');
            }
        }
        out.append('\n');
    }

    private static String lock(LockInfo l) {
        return String.format("<0x%08x> (a %s)", l.getIdentityHashCode(), l.getClassName());
    }
}
