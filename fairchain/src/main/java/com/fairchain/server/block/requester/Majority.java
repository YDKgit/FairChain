package com.fairchain.server.block.requester;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 노드 응답 다수결 — 전체 노드 수의 과반이 같은 값이어야 채택 (3대 → 2대, 5대 → 3대, 9대 → 5대).
 *
 * 기록(Raft)이 과반 저장으로 확정되므로 조회도 같은 기준을 쓴다 (2026-11-23 팀 결정, 그 전에는 2/3).
 * 2/3이면 노드가 5대 이상일 때 "기록은 되는데 조회는 안 되는" 구간이 생긴다 (9대에서 4대가 꺼지면 예매는 되고 체크인은 안 됨).
 * 꺼진 노드도 전체 수에 넣어 세므로, 과반이 꺼지면 남은 노드가 모두 같은 답을 해도 채택하지 않는다.
 */
public final class Majority {

    private Majority() {}

    /** ⌊n/2⌋ + 1 */
    public static int quorum(int nodeCount) {
        return nodeCount / 2 + 1;
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
                .findFirst();
    }
}
