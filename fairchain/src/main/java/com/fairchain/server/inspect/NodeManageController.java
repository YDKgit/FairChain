package com.fairchain.server.inspect;

import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.server.admin.entity.OperatorAccount;
import com.fairchain.server.admin.service.AuthService;
import com.fairchain.server.block.requester.NodeMembershipRequester;
import com.fairchain.server.common.ErrorCode;
import com.fairchain.server.common.FairChainException;
import com.fairchain.server.common.config.FairChainProperties;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 점검 화면(inspector.html)의 노드 추가 · 해지. 블록체인은 FairChain이 운영하므로 FairChain 운영자가 한다.
 *
 * 운영자 계정은 고객사마다 있으므로, 대시보드 토큰만으로는 부족하다 — 고객사 운영자가 노드를 붙이거나 뺄 수 있으면 안 된다.
 * 그래서 설정에 적은 운영자(fairchain.nodes.managers, 비우면 seed 운영자)만 할 수 있다.
 * 값을 바꾸는 요청이라 토큰은 주소가 아니라 헤더로 받는다 (주소는 접속 기록에 남는다).
 */
@RestController
@RequestMapping("/dashboard/nodes")
public class NodeManageController {

    public static final String TOKEN_HEADER = "X-Dashboard-Token";

    private final AuthService authService;
    private final NodeMembershipRequester membership;
    private final FairChainProperties properties;

    public NodeManageController(AuthService authService, NodeMembershipRequester membership, FairChainProperties properties) {
        this.authService = authService;
        this.membership = membership;
        this.properties = properties;
    }

    /** @param url 새 노드의 주소 (예: http://192.168.0.14:9001) */
    public record AddNodeRequest(String url) {}

    public record Access(boolean canManage) {}

    /** 이 운영자가 노드를 추가 · 해지할 수 있는지 — 화면이 버튼을 보일지 정한다 */
    @GetMapping("/access")
    public Access access(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return new Access(isManager(authService.authenticateDashboardToken(token)));
    }

    /** 노드 추가: 주소만 받는다. 그 노드가 진짜인지는 리더 층 리더가 확인한다. 따라잡기를 기다리므로 답이 늦을 수 있다 */
    @PostMapping
    public MembershipResultDto add(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                   @RequestBody AddNodeRequest request) {
        requireManager(token);
        return membership.add(request == null ? null : request.url());
    }

    /** 노드 해지. 남는 팀이 3개 미만이 되면 리더가 거부한다 */
    @DeleteMapping("/{id}")
    public MembershipResultDto remove(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                      @PathVariable("id") String id) {
        requireManager(token);
        return membership.remove(id);
    }

    private void requireManager(String token) {
        if (!isManager(authService.authenticateDashboardToken(token))) {
            throw new FairChainException(ErrorCode.FORBIDDEN, "노드를 추가 · 해지할 수 있는 운영자가 아닙니다");
        }
    }

    private boolean isManager(OperatorAccount account) {
        List<String> managers = properties.nodes() == null ? null : properties.nodes().managers();
        if (managers != null && !managers.isEmpty()) {
            return managers.contains(account.getLoginId());
        }
        FairChainProperties.Seed seed = properties.seed();
        return seed != null && seed.operatorLoginId() != null && seed.operatorLoginId().equals(account.getLoginId());
    }
}
