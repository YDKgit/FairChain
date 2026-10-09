package com.fairchain.api.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class Sha256RecordHasherTest {

    private final RecordHasher hasher = new Sha256RecordHasher();

    @Test
    void 같은_입력이면_같은_지문() {
        assertEquals(hasher.hash("A", "B"), hasher.hash("A", "B"));
        assertEquals(64, hasher.hash("A").length());
    }

    @Test
    void 필드_경계가_다르면_다른_지문() {
        assertNotEquals(hasher.hash("a|b", "c"), hasher.hash("a", "b|c"));
        assertNotEquals(hasher.hash("ab", "c"), hasher.hash("a", "bc"));
    }

    @Test
    void null과_빈문자열은_다른_지문() {
        assertNotEquals(hasher.hash((String) null), hasher.hash(""));
    }

    @Test
    void 필드_하나만_바뀌어도_다른_지문() {
        assertNotEquals(hasher.hash("seat-1", "user-7", "PURCHASE"), hasher.hash("seat-1", "user-8", "PURCHASE"));
    }

    @Test
    void 좌석키는_고객사와_좌석으로_정해진다() {
        assertEquals(hasher.recordKey("client-1", 10L), hasher.recordKey("client-1", 10L));
        assertNotEquals(hasher.recordKey("client-1", 10L), hasher.recordKey("client-2", 10L));
    }
}
