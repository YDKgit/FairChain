package com.fairchain.node.diag;

import com.fairchain.nodeapi.BlockRecordApi;
import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.nodeapi.NodeRaftApi;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * 이 노드가 받는 HTTP 요청을 종류별로 센다 — 상태 로그({@link NodeHealthLog})가 몇 초마다 꺼내 쓴다.
 * 요청 하나하나는 접근 로그(Tomcat access log)에 남고, 여기서는 "지금 몇 개가 걸려 있는지 · 가장 오래 걸린 것"만 본다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestStats extends OncePerRequestFilter {

    /** 요청 종류 (로그에 쓰는 이름) */
    public enum Kind {
        WRITE("기록"), VERIFY("검증"), RAFT("Raft"), STATUS("상태"), OTHER("기타");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** 한 구간(마지막으로 꺼낸 뒤부터)의 종류별 집계 */
    public record Window(long[] count, long[] failed, long[] totalMillis, long[] maxMillis) {
        public long total() {
            long sum = 0;
            for (long c : count) {
                sum += c;
            }
            return sum;
        }

        public long totalFailed() {
            long sum = 0;
            for (long f : failed) {
                sum += f;
            }
            return sum;
        }

        /** 가장 느린 요청의 종류 (요청이 없으면 null) */
        public Kind slowest() {
            Kind kind = null;
            for (Kind k : Kind.values()) {
                if (count[k.ordinal()] > 0 && (kind == null || maxMillis[k.ordinal()] > maxMillis[kind.ordinal()])) {
                    kind = k;
                }
            }
            return kind;
        }
    }

    private static final int KINDS = Kind.values().length;

    private final LongAdder[] count = adders();
    private final LongAdder[] failed = adders();
    private final LongAdder[] totalMillis = adders();
    private final LongAccumulator[] maxMillis = new LongAccumulator[KINDS];
    /** 처리 중인 요청: 번호 → 시작 시각(nanoTime) */
    private final Map<Long, Long> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public RequestStats() {
        for (int i = 0; i < KINDS; i++) {
            maxMillis[i] = new LongAccumulator(Math::max, 0);
        }
    }

    private static LongAdder[] adders() {
        LongAdder[] a = new LongAdder[KINDS];
        for (int i = 0; i < KINDS; i++) {
            a[i] = new LongAdder();
        }
        return a;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        long id = sequence.incrementAndGet();
        long started = System.nanoTime();
        inFlight.put(id, started);
        boolean thrown = true;
        try {
            chain.doFilter(req, resp);
            thrown = false;
        } finally {
            inFlight.remove(id);
            record(kind(req.getMethod(), req.getRequestURI()), (System.nanoTime() - started) / 1_000_000,
                    thrown || resp.getStatus() >= 500);
        }
    }

    void record(Kind kind, long millis, boolean fail) {
        int i = kind.ordinal();
        count[i].increment();
        totalMillis[i].add(millis);
        maxMillis[i].accumulate(millis);
        if (fail) {
            failed[i].increment();
        }
    }

    static Kind kind(String method, String uri) {
        if (uri == null) {
            return Kind.OTHER;
        }
        if (uri.startsWith(NodeRaftApi.MESSAGES)) {
            return Kind.RAFT;
        }
        if (uri.equals(BlockRecordApi.RECORDS) && "POST".equals(method)) {
            return Kind.WRITE;
        }
        if (uri.startsWith(BlockRecordApi.VERIFY) || uri.startsWith("/node/records/proof/")) {
            return Kind.VERIFY;     // verify · verify-batch · proof
        }
        if (uri.equals(NodeAdminApi.STATUS) || uri.equals(NodeAuditApi.LEDGER_STATUS)) {
            return Kind.STATUS;
        }
        return Kind.OTHER;
    }

    /** 지금 처리 중인 요청 수 */
    public int inFlight() {
        return inFlight.size();
    }

    /** 처리 중인 요청 가운데 가장 오래된 것이 몇 ms째인지 (없으면 0) */
    public long oldestInFlightMillis() {
        long now = System.nanoTime();
        long oldest = 0;
        for (long started : inFlight.values()) {
            oldest = Math.max(oldest, (now - started) / 1_000_000);
        }
        return oldest;
    }

    /** 마지막으로 꺼낸 뒤부터의 집계를 꺼내고 0으로 되돌린다 */
    public Window drain() {
        long[] c = new long[KINDS];
        long[] f = new long[KINDS];
        long[] t = new long[KINDS];
        long[] m = new long[KINDS];
        for (int i = 0; i < KINDS; i++) {
            c[i] = count[i].sumThenReset();
            f[i] = failed[i].sumThenReset();
            t[i] = totalMillis[i].sumThenReset();
            m[i] = maxMillis[i].getThenReset();
        }
        return new Window(c, f, t, m);
    }
}
