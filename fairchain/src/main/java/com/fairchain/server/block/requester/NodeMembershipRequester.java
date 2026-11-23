package com.fairchain.server.block.requester;

import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.dto.MemberJoinDto;
import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.server.block.requester.NodeDirectory.Node;
import com.fairchain.server.common.config.FairChainProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 리더 층에 노드를 추가 · 해지한다 (점검 화면의 "노드 추가" · "해지"). 블록체인은 FairChain이 운영하므로 FairChain 운영자가 한다.
 *
 * FairChain이 하는 일은 노드에게 요청을 전달하는 것뿐이다. 새 노드가 진짜인지 확인하고(공유 비밀값 · 개인키), learner로 붙여
 * 따라잡게 한 뒤 투표 멤버로 올리는 일은 리더 층 리더가 표준 Raft 멤버 변경으로 한다. 해지도 같다 (팀 3개 미만이 되면 리더가 거부).
 * 아무 노드에나 보내면 리더에게 넘어간다.
 */
@Component
public class NodeMembershipRequester {

    private static final Logger log = LoggerFactory.getLogger(NodeMembershipRequester.class);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");
    /** 가입은 새 노드가 블록을 다 따라잡을 때까지(최대 60초) 답이 오지 않는다 — 보통 노드 호출 제한(2초)과 따로 둔다 */
    private static final int ANSWER_MILLIS = 100_000;

    /** 노드 한 곳에 관리 요청을 보낸다. 응답이 없으면 예외 */
    @FunctionalInterface
    public interface Sender {
        MembershipResultDto send(String nodeUrl, HttpMethod method, String path, Object body);
    }

    private final NodeDirectory directory;
    private final Sender sender;

    @Autowired
    public NodeMembershipRequester(NodeDirectory directory, FairChainProperties properties) {
        this(directory, restSender(properties.nodes().apiKey()));
    }

    public NodeMembershipRequester(NodeDirectory directory, Sender sender) {
        this.directory = directory;
        this.sender = sender;
    }

    /**
     * @param address 새 노드의 주소 (예: http://192.168.0.14:9001). 스킴을 빼고 적으면 http로 본다.
     *                ID · 팀 · 공개키는 적지 않는다 — 리더가 그 노드에게 직접 확인해 받는다
     */
    public MembershipResultDto add(String address) {
        String url = address == null ? "" : address.trim();
        if (url.isEmpty() || url.length() > 200 || url.chars().anyMatch(Character::isWhitespace)) {
            return new MembershipResultDto(false, "노드 주소를 적어 주세요 (예: http://192.168.0.14:9001)");
        }
        if (!url.contains("://")) {
            url = "http://" + url;
        }
        MembershipResultDto result = toAnyNode(HttpMethod.POST, NodeAdminApi.MEMBERS, new MemberJoinDto(null, null, url, null));
        log.info("노드 추가 요청 {} → {}: {}", url, result.accepted() ? "가입" : "거부", result.message());
        directory.refresh();
        return result;
    }

    public MembershipResultDto remove(String nodeId) {
        if (nodeId == null || !ID.matcher(nodeId).matches()) {
            return new MembershipResultDto(false, "노드 ID가 필요합니다");
        }
        MembershipResultDto result = toAnyNode(HttpMethod.DELETE, NodeAdminApi.memberPath(nodeId), null);
        log.info("노드 해지 요청 {} → {}: {}", nodeId, result.accepted() ? "해지" : "거부", result.message());
        directory.refresh();
        return result;
    }

    /**
     * 리더부터 차례로 보내 처음 받은 답. 리더가 아닌 노드는 리더에게 넘겨서 받은 답을 준다.
     * "리더가 아니다" · "Raft에 참여하고 있지 않다"(빠진 노드)는 요청을 다루지 못했다는 뜻이라 다음 노드에 다시 보낸다.
     * 그 밖의 거부(가짜 노드 · 팀 3개 미만 등)는 리더의 결정이므로 그대로 돌려준다
     */
    private MembershipResultDto toAnyNode(HttpMethod method, String path, Object body) {
        String leader = directory.leaderId();
        List<Node> order = new ArrayList<>(directory.nodes());
        order.sort(Comparator.comparing(n -> leader == null || !leader.equals(n.id())));
        MembershipResultDto refused = null;
        for (Node node : order) {
            try {
                MembershipResultDto result = sender.send(node.url(), method, path, body);
                if (result == null) {
                    continue;
                }
                if (result.accepted() || !notHandled(result)) {
                    return result;
                }
                refused = result;
            } catch (RuntimeException e) {
                log.debug("노드 {}에 관리 요청 실패, 다음 노드 시도: {}", node.url(), e.getMessage());
            }
        }
        return refused != null ? refused : new MembershipResultDto(false, "응답하는 노드가 없습니다");
    }

    private static boolean notHandled(MembershipResultDto r) {
        String message = r.message() == null ? "" : r.message();
        return message.contains("NOT_LEADER") || message.contains("참여하고 있지 않다");
    }

    private static Sender restSender(String secret) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(ANSWER_MILLIS);
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        if (secret != null && !secret.isBlank()) {
            builder.defaultHeader(NodeHeaders.NODE_SECRET, secret);
        }
        RestClient http = builder.build();
        return (nodeUrl, method, path, body) -> {
            RestClient.RequestBodySpec request = http.method(method).uri(nodeUrl + path);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.retrieve().body(MembershipResultDto.class);
        };
    }
}
