package com.fairchain.server.inspect;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 최근 요청을 메모리에 최대 500건 보관 (서버를 다시 켜면 비워진다) */
@Component
public class RequestLogStore {

    private static final int MAX = 500;

    private final Deque<RequestLogEntry> entries = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();

    public long nextId() {
        return sequence.incrementAndGet();
    }

    public synchronized void add(RequestLogEntry entry) {
        entries.addFirst(entry);
        while (entries.size() > MAX) {
            entries.removeLast();
        }
    }

    /** 최신순 */
    public synchronized List<RequestLogEntry> recent() {
        return new ArrayList<>(entries);
    }
}
