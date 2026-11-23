package com.fairchain.server.inspect;

import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.server.admin.entity.OperatorAccount;
import com.fairchain.server.admin.service.AuthService;
import com.fairchain.server.block.requester.NodeMembershipRequester;
import com.fairchain.server.common.ErrorCode;
import com.fairchain.server.common.FairChainException;
import com.fairchain.server.common.config.FairChainProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노드 추가 · 해지는 FairChain 운영자만: 운영자 계정은 고객사마다 있으므로 대시보드 토큰만으로는 부족하다.
 */
class NodeManageControllerTest {

    private final AuthService auth = mock(AuthService.class);
    private final NodeMembershipRequester membership = mock(NodeMembershipRequester.class);

    private NodeManageController controller(List<String> managers, String seedOperator) {
        return new NodeManageController(auth, membership, new FairChainProperties(null, null,
                new FairChainProperties.Nodes(List.of("http://localhost:9001"), "secret", Duration.ofSeconds(2), managers),
                new FairChainProperties.Seed("fairchain", "key", seedOperator, "pw")));
    }

    private void token(String token, String loginId, String clientId) {
        when(auth.authenticateDashboardToken(token)).thenReturn(new OperatorAccount(clientId, loginId, "hash"));
    }

    @Test
    void theSeedOperatorManagesNodesByDefault() {
        NodeManageController c = controller(null, "admin");
        token("t-admin", "admin", "fairchain");
        when(membership.add("http://10.0.0.4:9001")).thenReturn(new MembershipResultDto(true, "D1 (팀 D) 가입"));
        when(membership.remove("D1")).thenReturn(new MembershipResultDto(true, "D1 해지"));

        assertTrue(c.access("t-admin").canManage());
        assertTrue(c.add("t-admin", new NodeManageController.AddNodeRequest("http://10.0.0.4:9001")).accepted());
        assertTrue(c.remove("t-admin", "D1").accepted());
    }

    @Test
    void anotherClientsOperatorCannotAddOrRemoveNodes() {
        NodeManageController c = controller(null, "admin");
        token("t-customer", "concert-operator", "concert-site");

        assertFalse(c.access("t-customer").canManage());
        FairChainException add = assertThrows(FairChainException.class,
                () -> c.add("t-customer", new NodeManageController.AddNodeRequest("http://10.0.0.66:9001")));
        assertEquals(ErrorCode.FORBIDDEN, add.errorCode());
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(FairChainException.class, () -> c.remove("t-customer", "A1")).errorCode());
        verify(membership, never()).add(any());
        verify(membership, never()).remove(any());
    }

    @Test
    void whenManagersAreListedOnlyTheyMayManage() {
        NodeManageController c = controller(List.of("chain-admin"), "admin");
        token("t-admin", "admin", "fairchain");
        token("t-chain", "chain-admin", "fairchain");

        assertFalse(c.access("t-admin").canManage(), "명단을 적으면 seed 운영자도 명단에 있어야 한다");
        assertTrue(c.access("t-chain").canManage());
    }

    @Test
    void withoutAValidTokenNothingReachesTheNodes() {
        NodeManageController c = controller(null, "admin");
        when(auth.authenticateDashboardToken(null)).thenThrow(new FairChainException(ErrorCode.UNAUTHORIZED, "대시보드 토큰이 없습니다"));

        assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(FairChainException.class,
                () -> c.add(null, new NodeManageController.AddNodeRequest("http://10.0.0.4:9001"))).errorCode());
        verify(membership, never()).add(any());
    }
}
