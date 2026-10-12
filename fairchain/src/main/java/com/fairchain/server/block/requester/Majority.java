package com.fairchain.server.block.requester;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 노드 응답 다수결 — 전체 노드 수의 2/3 이상이 같은 값이어야 채택 (3대면 2대) */
public final class Majority {

    private Majority() {}

    public static int quorum(int nodeCount) {
        return nodeCount * 2 / 3 + (nodeCount * 2 % 3 == 0 ? 0 : 1);
    }

    /** null(응답 없음)은 세지 않는다 */
    public static <T> Optional<T> pick(List<T> responses, int nodeCount) {
        Map<T, Integer> counts = new HashMap<>();
        for (T response : responses) {
            if (response != null) {
                counts.merge(response, 1, Integer::sum);
            }
        }
        int quorum = quorum(nodeCount);
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= quorum)
                .map(Map.Entry::getKey)
                .filter(Objects::nonNull)
                .findFirst();
    }
}
