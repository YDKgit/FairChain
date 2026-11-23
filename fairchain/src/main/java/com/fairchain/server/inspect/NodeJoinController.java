package com.fairchain.server.inspect;

import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.server.block.requester.NodeMembershipRequester;
import com.fairchain.server.common.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 고객사가 자기 API Key로 노드를 추가한다 (2026-11-23 팀 결정) — 노드 가입 화면(node-join.html)과 고객사 서버가 쓴다.
 *
 * /api/** 이므로 ApiKeyInterceptor가 먼저 X-API-KEY를 확인한다. 등록된 고객사가 아니면 여기까지 오지 못한다(401).
 * API Key는 "누가 요청했는가"만 증명한다. 그 주소의 노드가 진짜인지는 지금처럼 리더 층 리더가 직접 확인한다
 * (노드 공유 비밀값 HMAC + 그 노드의 개인키 서명) — 그래서 API Key가 있어도 가짜 노드는 붙지 않는다.
 * 해지는 여기서 받지 않는다. 점검 화면에서 FairChain 운영자만 한다 (NodeManageController).
 */
@RestController
@RequestMapping("/api/nodes")
public class NodeJoinController {

    private static final Logger log = LoggerFactory.getLogger(NodeJoinController.class);

    private final NodeMembershipRequester membership;
    private final InspectorService inspectorService;

    public NodeJoinController(NodeMembershipRequester membership, InspectorService inspectorService) {
        this.membership = membership;
        this.inspectorService = inspectorService;
    }

    /** @param url 새 노드의 주소 (예: http://192.168.0.14:9001) */
    public record JoinRequest(String url) {}

    /** 가입 화면에 보여 줄 노드 한 줄 */
    /** @param teamMembers 이 팀장에 붙어 있는 팀원 노드 수 (모르면 null) */
    public record NodeRow(String id, String team, String url, boolean reachable, String role, Integer teamMembers, Long height) {}

    /** 노드 추가: 주소만 받는다. 따라잡기를 기다리므로 답이 늦을 수 있다 */
    @PostMapping
    public MembershipResultDto join(@RequestBody(required = false) JoinRequest request, HttpServletRequest http) {
        String url = request == null ? null : request.url();
        MembershipResultDto result = membership.add(url);
        log.info("고객사 {} 의 노드 추가 요청 {} → {}", ClientContext.find(http), url, result.accepted() ? "가입" : "거부");
        return result;
    }

    /** 지금 리더 층에 있는 노드 */
    @GetMapping
    public List<NodeRow> nodes() {
        return inspectorService.nodes().stream()
                .map(n -> new NodeRow(n.id(), n.raft() == null ? null : n.raft().team(), n.url(), n.reachable(),
                        n.raft() == null ? null : n.raft().raftRole(),
                        n.raft() == null || n.raft().teamMembers() == null ? null : n.raft().teamMembers().size(),
                        n.status() == null ? null : n.status().height()))
                .toList();
    }
}
