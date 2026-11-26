package com.fairchain.node.diag;

import com.fairchain.node.config.NodeSettings;
import com.fairchain.node.raft.RaftNodeHolder;
import com.fairchain.node.service.NodeService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 노드 상태를 몇 초마다 한 줄씩 로그에 남기고, 멈춘 것 같으면 그 순간의 스레드 덤프를 파일로 남긴다.
 *
 * <p>한 줄에 들어가는 것: Raft 역할 · 임기 · 리더 · 마지막 블록, 그 구간에 받은 요청(종류별 건수 · 실패 · 가장 느린 요청),
 * 처리 중인 요청, DB 연결(사용 · 대기), 힙 · 스레드 · GC · CPU. 나중에 로그만 보고 "그때 무엇이 쌓였는지"를 알 수 있게 한다.
 *
 * <p>멈춤 의심: DB 연결을 기다리는 스레드가 3초 넘게 있거나, 10초 넘게 끝나지 않은 요청이 있을 때 (2026-11-25에 노드가
 * 30초씩 멈춘 교착의 모양). 덤프는 1분에 한 번까지. 이 스레드는 DB를 쓰지 않으므로 연결이 바닥나도 돈다.
 */
@Component
public class NodeHealthLog {

    private static final Logger log = LoggerFactory.getLogger("com.fairchain.node.health");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int STALL_SECONDS = 3;
    private static final long DUMP_EVERY_MILLIS = 60_000;

    private final NodeSettings settings;
    private final RaftNodeHolder raft;
    private final NodeService nodes;
    private final RequestStats requests;
    private final DataSource dataSource;
    private final long intervalMillis;
    private final long stallRequestMillis;
    private final String dumpDir;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "node-health");
        t.setDaemon(true);
        return t;
    });

    private long lastLine = System.currentTimeMillis();
    private long lastDump = 0;
    private int suspiciousSeconds = 0;
    private long gcCountBefore = gcCount();
    private long gcMillisBefore = gcMillis();

    public NodeHealthLog(NodeSettings settings, RaftNodeHolder raft, NodeService nodes, RequestStats requests,
                         DataSource dataSource,
                         @Value("${node.diag.health-log-ms:5000}") long intervalMillis,
                         @Value("${node.diag.stall-request-ms:10000}") long stallRequestMillis,
                         @Value("${node.diag.dump-dir:}") String dumpDir) {
        this.settings = settings;
        this.raft = raft;
        this.nodes = nodes;
        this.requests = requests;
        this.dataSource = dataSource;
        this.intervalMillis = intervalMillis;
        this.stallRequestMillis = stallRequestMillis;
        this.dumpDir = dumpDir == null ? "" : dumpDir.trim();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (intervalMillis > 0) {
            scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    private void tick() {
        try {
            HikariPoolMXBean pool = pool();
            int waiting = pool == null ? 0 : pool.getThreadsAwaitingConnection();
            long oldest = requests.oldestInFlightMillis();
            suspiciousSeconds = waiting > 0 || oldest >= stallRequestMillis ? suspiciousSeconds + 1 : 0;
            long now = System.currentTimeMillis();
            if (suspiciousSeconds >= STALL_SECONDS && now - lastDump >= DUMP_EVERY_MILLIS) {
                lastDump = now;
                stallReport(waiting, oldest);
            }
            if (now - lastLine >= intervalMillis) {
                long seconds = Math.max(1, (now - lastLine) / 1000);
                lastLine = now;
                log.info("{}", line(pool, oldest, seconds));
            }
        } catch (RuntimeException | Error e) {
            log.debug("상태 로그 실패: {}", e.toString());
        }
    }

    /** 로그 한 줄 */
    String line(HikariPoolMXBean pool, long oldestMillis, long seconds) {
        StringBuilder s = new StringBuilder();
        s.append(raftPart());

        RequestStats.Window w = requests.drain();
        s.append(" | 요청 ").append(seconds).append("초간");
        for (RequestStats.Kind k : RequestStats.Kind.values()) {
            s.append(' ').append(k.label).append(' ').append(w.count()[k.ordinal()]);
        }
        s.append(" · 실패 ").append(w.totalFailed());
        RequestStats.Kind slowest = w.slowest();
        if (slowest != null) {
            s.append(" · 가장 느림 ").append(w.maxMillis()[slowest.ordinal()]).append("ms(").append(slowest.label).append(')');
        }
        s.append(" · 처리 중 ").append(requests.inFlight());
        if (oldestMillis >= 1000) {
            s.append("(가장 오래 ").append(oldestMillis / 1000).append("초)");
        }

        if (pool != null) {
            s.append(" | DB 연결 ").append(pool.getActiveConnections()).append('/').append(pool.getTotalConnections())
                    .append(" 사용 · 대기 ").append(pool.getThreadsAwaitingConnection());
        }

        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long gcCount = gcCount();
        long gcMillis = gcMillis();
        s.append(" | 힙 ").append(heap.getUsed() >> 20).append('/').append(heap.getMax() >> 20).append("MB")
                .append(" · 스레드 ").append(ManagementFactory.getThreadMXBean().getThreadCount())
                .append(" · GC ").append(gcCount - gcCountBefore).append("회 ").append(gcMillis - gcMillisBefore).append("ms");
        gcCountBefore = gcCount;
        gcMillisBefore = gcMillis;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            double cpu = os.getProcessCpuLoad();
            if (cpu >= 0) {
                s.append(" · CPU ").append(Math.round(cpu * 100)).append('%');
            }
        }
        return s.toString();
    }

    /** Raft 상태 — 메모리에 있는 값만 쓴다 (DB를 쓰면 연결이 바닥났을 때 이 스레드도 멈춘다) */
    private String raftPart() {
        if (!settings.leaderLayer()) {
            return "팀원";
        }
        if (raft.outReason() != null) {
            return "빠짐(" + raft.outReason() + ")";
        }
        String term = raft.term().map(t -> String.valueOf(t.getTerm())).orElse("-");
        String leader = raft.leaderId().orElse("-");
        long block = raft.stateMachine() == null ? -1 : raft.stateMachine().logHead().index();
        return nodes.raftRole() + " · 임기 " + term + " · 리더 " + leader + " · 블록 " + block;
    }

    private void stallReport(int waiting, long oldestMillis) {
        ThreadInfo[] threads = ThreadDumps.take();
        ThreadDumps.Counts c = ThreadDumps.count(threads);
        String file = writeDump(threads);
        log.warn("멈춤 의심: DB 연결 대기 {} · 처리 중 요청 {}(가장 오래 {}초) · 잠금 대기(BLOCKED) 스레드 {} · "
                        + "블록을 붙이는 스레드가 연결 대기 {} — 스레드 덤프: {}",
                waiting, requests.inFlight(), oldestMillis / 1000, c.blocked(),
                c.ledgerWriterWaitsForConnection() ? "예 (교착 모양)" : "아니오",
                file == null ? "남기지 않음 (node.diag.dump-dir 없음)" : file);
    }

    private String writeDump(ThreadInfo[] threads) {
        if (dumpDir.isEmpty()) {
            return null;
        }
        try {
            Path dir = Path.of(dumpDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(settings.id() + "-threads-" + LocalDateTime.now().format(FILE_TIME) + ".txt");
            Files.writeString(file, "스레드 덤프 " + LocalDateTime.now() + " · 노드 " + settings.id() + "\n\n"
                    + ThreadDumps.format(threads));
            return file.toString();
        } catch (IOException | RuntimeException e) {
            log.debug("스레드 덤프를 쓰지 못함: {}", e.toString());
            return null;
        }
    }

    private HikariPoolMXBean pool() {
        if (dataSource instanceof HikariDataSource h) {
            return h.getHikariPoolMXBean();     // 첫 연결 전에는 null
        }
        return null;
    }

    private static long gcCount() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, gc.getCollectionCount());
        }
        return n;
    }

    private static long gcMillis() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, gc.getCollectionTime());
        }
        return n;
    }
}
