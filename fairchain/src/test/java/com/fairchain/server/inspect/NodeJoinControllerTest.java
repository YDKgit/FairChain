package com.fairchain.server.inspect;

import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.server.block.requester.NodeMembershipRequester;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 고객사가 API Key로 노드를 추가하는 길 (/api/nodes). API Key 검사는 ApiKeyInterceptor가 하고(FairChainApiTest),
 * 여기서는 주소가 그대로 노드 층으로 넘어가고 노드의 판단이 그대로 돌아오는지 본다.
 */
class NodeJoinControllerTest {

    private final NodeMembershipRequester membership = mock(NodeMembershipRequester.class);
    private final InspectorService inspector = mock(InspectorService.class);
    private final NodeJoinController controller = new NodeJoinController(membership, inspector);

    @Test
    void theAddressGoesToTheNodesAndTheirAnswerComesBack() {
        when(membership.add("http://10.0.0.4:9001")).thenReturn(new MembershipResultDto(true, "D1 (팀 D) 가입"));

        MembershipResultDto result = controller.join(new NodeJoinController.JoinRequest("http://10.0.0.4:9001"), new MockHttpServletRequest());

        assertTrue(result.accepted());
        assertEquals("D1 (팀 D) 가입", result.message());
    }

    @Test
    void aNodeTheLeaderRejectsStaysOutEvenWithAValidApiKey() {
        when(membership.add("http://10.0.0.66:9001"))
                .thenReturn(new MembershipResultDto(false, "노드 공유 비밀값을 모른다 — FairChain이 띄운 노드가 아니다"));

        assertFalse(controller.join(new NodeJoinController.JoinRequest("http://10.0.0.66:9001"), new MockHttpServletRequest()).accepted());
    }

    @Test
    void anEmptyRequestIsAnsweredByTheSameAddressCheck() {
        when(membership.add(null)).thenReturn(new MembershipResultDto(false, "노드 주소를 적어 주세요"));

        assertFalse(controller.join(null, new MockHttpServletRequest()).accepted());
        verify(membership).add(null);
    }

    @Test
    void theNodeListShowsUnreachableNodesToo() {
        when(inspector.nodes()).thenReturn(List.of(
                new InspectorService.NodeView("A1", "http://localhost:9001", false, null, List.of(), "연결 거부", null)));

        List<NodeJoinController.NodeRow> rows = controller.nodes();

        assertEquals(1, rows.size());
        assertEquals("A1", rows.get(0).id());
        assertFalse(rows.get(0).reachable());
    }
}
