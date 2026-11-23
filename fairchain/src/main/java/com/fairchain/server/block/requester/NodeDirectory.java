package com.fairchain.server.block.requester;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.dto.MemberInfoDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * FairChain이 지금 묻는 노드 목록 (리더 층의 투표 멤버).
 *
 * 설정의 주소 목록({@code fairchain.nodes.urls})은 출발점일 뿐이다. 몇 초마다 노드에게 상태를 묻고, 리더가 알려 주는
 * 멤버 명단(Raft 로그로 정해진 값)에 맞춘다 — 노드가 가입 · 해지돼도 FairChain을 다시 켜지 않는다
 * (etcd 클라이언트가 멤버 목록을 받아 접속 주소를 맞추는 것과 같다).
 *
 * <ul>
 *   <li>명단은 리더의 답만 쓴다. 리더라고 답한 노드가 둘이면 임기가 큰 쪽 (예전 리더가 잠깐 남아 있을 수 있다).
 *       리더가 없으면(선거 중) 목록을 그대로 둔다</li>
 *   <li>learner는 넣지 않는다 — 따라잡고 투표 멤버가 된 뒤에 묻는다. 판정으로 제외된 노드는 멤버이므로 그대로 둔다
 *       (그 노드는 조회에 503으로 답해 "응답 없음"으로 센다)</li>
 *   <li>설정에 적은 노드는 설정의 주소를 계속 쓴다 (노드끼리 쓰는 주소와 FairChain이 쓰는 주소가 다를 수 있다).
 *       새로 알게 된 노드만 명단의 주소를 쓴다</li>
 * </ul>
 *
 * 호출하는 쪽은 요청 하나를 처리하는 동안 {@link #nodes()}로 받은 목록 하나만 쓴다 (도중에 목록이 바뀌어도 번호가 어긋나지 않게).
 */
public class NodeDirectory {

    private static final Logger log = LoggerFactory.getLogger(NodeDirectory.class);

    /**
     * @param id  노드 ID (아직 응답을 못 받아 모르면 null)
     * @param url FairChain이 이 노드에 접속하는 주소
     */
    public record Node(String id, String url, HttpRequester http) {}

    private final Function<String, HttpRequester> connector;
    private volatile List<Node> nodes;
    private volatile String leaderId;
    private ScheduledExecutorService poller;

    /**
     * @param seedUrls  처음에 아는 노드 주소
     * @param connector 주소 → 그 노드로 가는 HttpRequester (비밀값 헤더 포함)
     */
    public NodeDirectory(List<String> seedUrls, Function<String, HttpRequester> connector) {
        this.connector = connector;
        List<Node> seeds = new ArrayList<>();
        for (String raw : seedUrls) {
            String url = raw.trim().replaceAll("/+$", "");
            if (!url.isEmpty() && seeds.stream().noneMatch(n -> n.url().equals(url))) {
                seeds.add(new Node(null, url, connector.apply(url)));
            }
        }
        this.nodes = List.copyOf(seeds);
    }

    /** 바뀌지 않는 목록 (테스트 · 노드 주소를 고정해 쓰는 경우) */
    public static NodeDirectory fixed(List<HttpRequester> requesters) {
        NodeDirectory directory = new NodeDirectory(List.of(), url -> {
            throw new IllegalStateException("fixed node list");
        });
        List<Node> list = new ArrayList<>();
        for (int i = 0; i < requesters.size(); i++) {
            list.add(new Node(null, "node" + (i + 1), requesters.get(i)));
        }
        directory.nodes = List.copyOf(list);
        return directory;
    }

    /** 지금의 노드 목록 (바뀌지 않는 사본) */
    public List<Node> nodes() {
        return nodes;
    }

    /** 마지막으로 확인한 리더 층 리더의 ID (아직 모르면 null). 그사이 바뀌었을 수 있다 — 먼저 물어볼 노드를 고르는 데만 쓴다 */
    public String leaderId() {
        return leaderId;
    }

    /**
     * periodMillis마다 목록을 맞춘다 (0 이하면 하지 않는다). 꺼진 노드는 응답 제한 시간까지 기다리게 하므로, 입장 허용 · 블록 기록이
     * 도는 공용 스케줄러(@Scheduled, 스레드 1개)가 아니라 따로 도는 스레드에서 한다
     */
    public synchronized void start(long periodMillis) {
        if (periodMillis <= 0 || poller != null) {
            return;
        }
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "node-directory");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(() -> {
            try {
                refresh();
            } catch (RuntimeException e) {
                log.debug("노드 목록 갱신 실패: {}", e.toString());
            }
        }, 0, periodMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
    }

    /** 노드에게 물어 목록을 맞춘다. 노드 구성(주소)이 바뀌었으면 true */
    public synchronized boolean refresh() {
        List<Node> current = nodes;
        List<Node> known = new ArrayList<>(current.size());
        NodeStatusDto leader = null;
        for (Node n : current) {
            NodeStatusDto s = status(n);
            known.add(s != null && s.id() != null && !s.id().equals(n.id()) ? new Node(s.id(), n.url(), n.http()) : n);
            if (s != null && "LEADER".equals(s.raftRole()) && s.members() != null
                    && (leader == null || term(s) > term(leader))) {
                leader = s;
            }
        }
        List<Node> next = leader == null ? known : members(leader, known);
        if (leader != null) {
            leaderId = leader.id();
        }
        if (next.equals(current)) {
            return false;
        }
        nodes = List.copyOf(next);
        if (!urls(next).equals(urls(current))) {
            log.info("노드 목록이 바뀜: {} → {} (리더 {}의 멤버 명단)", describe(current), describe(next),
                    leader == null ? "-" : leader.id());
            return true;
        }
        return false;
    }

    /** 리더의 멤버 명단에서 투표 멤버만, 명단 순서대로 */
    private List<Node> members(NodeStatusDto leader, List<Node> known) {
        List<Node> next = new ArrayList<>();
        for (MemberInfoDto m : leader.members()) {
            if (!m.voting() || m.id() == null) {
                continue;
            }
            Node have = known.stream().filter(n -> m.id().equals(n.id())).findFirst().orElse(null);
            if (have == null && m.url() != null && !m.url().isBlank()) {
                String url = m.url().trim().replaceAll("/+$", "");
                // 설정에는 있는데 아직 응답을 못 받아 ID를 모르던 노드면 그 연결을 그대로 쓴다
                Node sameUrl = known.stream().filter(n -> n.id() == null && n.url().equals(url)).findFirst().orElse(null);
                have = new Node(m.id(), url, sameUrl != null ? sameUrl.http() : connector.apply(url));
            }
            if (have != null) {
                next.add(have);
            }
        }
        return next.isEmpty() ? known : next;
    }

    private static NodeStatusDto status(Node n) {
        try {
            return n.http().get(NodeAdminApi.STATUS, NodeStatusDto.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int term(NodeStatusDto s) {
        return s.term() == null ? 0 : s.term();
    }

    private static List<String> urls(List<Node> list) {
        return list.stream().map(Node::url).toList();
    }

    private static String describe(List<Node> list) {
        return list.stream().map(n -> n.id() == null ? n.url() : n.id()).toList().toString();
    }
}
