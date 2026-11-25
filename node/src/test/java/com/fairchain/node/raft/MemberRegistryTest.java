package com.fairchain.node.raft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 해지된 ID는 명단에서 빠지고 "해지된 ID"로 남는다 — 스냅샷으로 로그를 자른 뒤에도 */
class MemberRegistryTest {

    private static final List<MemberRecord> CONFIGURED = List.of(
            new MemberRecord("A1", "A", "http://a:9001", null),
            new MemberRecord("B1", "B", "http://b:9001", null),
            new MemberRecord("C1", "C", "http://c:9001", null));

    @Test
    void aRemovedIdStaysRemoved() {
        MemberRegistry registry = new MemberRegistry(CONFIGURED);
        registry.register(new MemberRecord("D1", "D", "http://d:9001", "key-d"));
        assertFalse(registry.isRemoved("D1"));

        registry.remove("D1");
        assertTrue(registry.isRemoved("D1"));
        assertTrue(registry.get("D1").isEmpty(), "주소 · 공개키는 명단에서 빠진다");
        assertFalse(registry.isRemoved("A1"));
        assertEquals(List.of("D1"), registry.removedIds());
    }

    @Test
    void theRemovedIdsSurviveASnapshot() {
        MemberRegistry leader = new MemberRegistry(CONFIGURED);
        leader.register(new MemberRecord("D1", "D", "http://d:9001", "key-d"));
        leader.remove("D1");
        leader.remove("C1");       // 처음 명단에 있던 노드도 해지할 수 있다
        LedgerSnapshot snapshot = leader.snapshot(10, "h");

        // 새로 들어온 노드: 설정 파일에는 C1이 아직 적혀 있다
        MemberRegistry joiner = new MemberRegistry(CONFIGURED);
        joiner.install(snapshot);
        assertTrue(joiner.isRemoved("D1"));
        assertTrue(joiner.isRemoved("C1"));
        assertTrue(joiner.get("C1").isEmpty(), "설정 파일에 남아 있어도 해지된 노드의 주소를 되살리지 않는다");
        assertTrue(joiner.get("A1").isPresent());
    }

    @Test
    void anOldSnapshotWithoutTheListStillInstalls() {
        MemberRegistry registry = new MemberRegistry(CONFIGURED);
        registry.install(new LedgerSnapshot(5, "h", List.of(new MemberRecord("A1", "A", "http://a:9001", "key-a")),
                Map.of(), List.of(), Map.of(), false, null));
        assertFalse(registry.isRemoved("A1"));
        assertEquals("key-a", registry.get("A1").orElseThrow().publicKey());
    }
}
