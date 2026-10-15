package com.fairchain.server.block.requester;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MajorityTest {

    @Test
    void 노드_3대면_2대가_정족수() {
        assertEquals(2, Majority.quorum(3));
        assertEquals(3, Majority.quorum(4));
    }

    @Test
    void 두_대가_같으면_채택() {
        assertEquals(Optional.of("A"), Majority.pick(List.of("A", "B", "A"), 3));
    }

    @Test
    void 응답_없는_노드는_세지_않음() {
        assertEquals(Optional.of("A"), Majority.pick(Arrays.asList("A", null, "A"), 3));
        assertEquals(Optional.empty(), Majority.pick(Arrays.asList("A", null, null), 3));
    }

    @Test
    void 모두_다르면_채택_안함() {
        assertEquals(Optional.empty(), Majority.pick(List.of("A", "B", "C"), 3));
    }
}
