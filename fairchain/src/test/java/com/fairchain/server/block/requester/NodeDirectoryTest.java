package com.fairchain.server.block.requester;

import com.fairchain.api.http.HttpRequester;
import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.dto.MemberInfoDto;
import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import com.fairchain.server.block.requester.NodeDirectory.Node;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 노드가 가입 · 해지되면 FairChain이 다시 켜지 않고 노드 목록을 맞춘다: 설정의 주소는 출발점이고, 그 뒤는 리더의 멤버 명단을 따른다.
 */
class NodeDirectoryTest {

    private static final String A = "http://10.0.0.1:9001";
    private static final String B = "http://10.0.0.2:9001";
    private static final String C = "http://10.0.0.3:9001";
    private static final String D = "http://10.0.0.4:9001";

    private final Map<String, HttpRequester> requesters = new HashMap<>();
    private final NodeDirectory directory = new NodeDirectory(List.of(A, B + "/", C), this::requester);

    private HttpRequester requester(String url) {
        return requesters.computeIfAbsent(url, u -> mock(HttpRequester.class));
    }

    private static MemberInfoDto member(String id, String url, boolean voting) {
        return new MemberInfoDto(id, id.substring(0, 1), url, "key-" + id, voting, false);
    }

    private void answers(String url, String id, String raftRole, int term, MemberInfoDto... members) {
        when(requester(url).get(NodeAdminApi.STATUS, NodeStatusDto.class)).thenReturn(new NodeStatusDto(id, id.substring(0, 1),
                "leader-layer", raftRole, term, null, List.of(members), List.of(), List.of(), List.of(), 0L, "0", "key-" + id,
                id, List.of(), null, false));
    }

    private void down(String url) {
        when(requester(url).get(NodeAdminApi.STATUS, NodeStatusDto.class)).thenThrow(new IllegalStateException("down"));
    }

    private List<String> urls() {
        return directory.nodes().stream().map(Node::url).toList();
    }

    private List<String> ids() {
        return directory.nodes().stream().map(Node::id).toList();
    }

    private static MemberInfoDto[] abc() {
        return new MemberInfoDto[]{member("A1", A, true), member("B1", B, true), member("C1", C, true)};
    }

    @Test
    void startsFromTheConfiguredAddresses() {
        assertEquals(List.of(A, B, C), urls());
        assertEquals(2, Majority.quorum(directory.nodes().size()), "3대면 2대가 같아야 채택");
    }

    @Test
    void aNodeThatJoinedIsAskedOnceItIsAVotingMember() {
        MemberInfoDto[] withLearner = {member("A1", A, true), member("B1", B, true), member("C1", C, true), member("D1", D, false)};
        answers(A, "A1", "LEADER", 2, withLearner);
        answers(B, "B1", "FOLLOWER", 2, withLearner);
        answers(C, "C1", "FOLLOWER", 2, withLearner);
        assertFalse(directory.refresh(), "learner는 아직 묻지 않는다");
        assertEquals(List.of("A1", "B1", "C1"), ids());

        MemberInfoDto[] promoted = {member("A1", A, true), member("B1", B, true), member("C1", C, true), member("D1", D + "/", true)};
        answers(A, "A1", "LEADER", 2, promoted);
        assertTrue(directory.refresh());
        assertEquals(List.of(A, B, C, D), urls());
        assertEquals(List.of("A1", "B1", "C1", "D1"), ids());
        assertEquals(3, Majority.quorum(directory.nodes().size()), "4대면 3대가 같아야 채택");
        assertSame(requester(D), directory.nodes().get(3).http());
        assertEquals("A1", directory.leaderId());
    }

    @Test
    void aNodeThatLeftIsNoLongerAsked() {
        answers(A, "A1", "FOLLOWER", 3, abc());
        answers(B, "B1", "LEADER", 3, member("A1", A, true), member("B1", B, true), member("D1", D, true));
        answers(C, "C1", "FOLLOWER", 2, abc());       // 해지된 노드는 예전 명단을 그대로 갖고 있다
        assertTrue(directory.refresh());
        assertEquals(List.of("A1", "B1", "D1"), ids());
        assertEquals(List.of(A, B, D), urls());
    }

    @Test
    void withoutALeaderTheListStays() {
        answers(A, "A1", "CANDIDATE", 4, member("A1", A, true));
        answers(B, "B1", "FOLLOWER", 3, abc());
        down(C);
        assertFalse(directory.refresh());
        assertEquals(List.of(A, B, C), urls());
        assertEquals(java.util.Arrays.asList("A1", "B1", null), ids(), "응답한 노드의 ID는 알아 둔다");
        assertNull(directory.leaderId());
    }

    @Test
    void anOldLeaderWithALowerTermIsIgnored() {
        answers(A, "A1", "LEADER", 5, member("A1", A, true), member("B1", B, true), member("C1", C, true), member("D1", D, true));
        answers(B, "B1", "LEADER", 4, abc());         // 끊겼다 돌아온 예전 리더
        answers(C, "C1", "FOLLOWER", 5, abc());
        assertTrue(directory.refresh());
        assertEquals(List.of("A1", "B1", "C1", "D1"), ids());
    }

    @Test
    void configuredAddressesWinOverAdvertisedOnesForKnownNodes() {
        // 노드끼리는 다른 주소(내부망)를 쓰더라도 FairChain은 설정에 적은 주소로 계속 접속한다
        answers(A, "A1", "LEADER", 1, member("A1", "http://internal-a:9001", true), member("B1", "http://internal-b:9001", true),
                member("C1", "http://internal-c:9001", true));
        answers(B, "B1", "FOLLOWER", 1);
        answers(C, "C1", "FOLLOWER", 1);
        assertFalse(directory.refresh());
        assertEquals(List.of(A, B, C), urls());
    }

    @Test
    void theOperatorAddsANodeByAddressOnlyAndTheListIsRefreshed() {
        answers(A, "A1", "FOLLOWER", 2, abc());
        answers(B, "B1", "LEADER", 2, abc());
        answers(C, "C1", "FOLLOWER", 2, abc());
        directory.refresh();

        List<String> asked = new ArrayList<>();
        List<Object> bodies = new ArrayList<>();
        NodeMembershipRequester membership = new NodeMembershipRequester(directory, (nodeUrl, method, path, body) -> {
            asked.add(method + " " + nodeUrl + path);
            bodies.add(body);
            if (method == HttpMethod.POST) {
                answers(B, "B1", "LEADER", 2, member("A1", A, true), member("B1", B, true), member("C1", C, true), member("D1", D, true));
            }
            return new MembershipResultDto(true, "ok");
        });

        assertTrue(membership.add(" 10.0.0.4:9001 ").accepted());
        assertEquals(List.of("POST " + B + NodeAdminApi.MEMBERS), asked, "리더에게 먼저 보낸다");
        assertEquals(new com.fairchain.nodeapi.dto.MemberJoinDto(null, null, D, null), bodies.get(0), "주소만 보낸다 — ID · 팀 · 공개키는 노드가 확인");
        assertEquals(List.of("A1", "B1", "C1", "D1"), ids(), "추가 뒤 바로 목록에 들어온다");

        assertTrue(membership.remove("D1").accepted());
        assertEquals("DELETE " + B + NodeAdminApi.memberPath("D1"), asked.get(1));
        assertFalse(membership.remove("../admin/resume").accepted(), "ID가 아닌 값은 노드로 보내지 않는다");
        assertFalse(membership.add("  ").accepted());
        assertEquals(2, asked.size());
    }

    @Test
    void aRequestANodeCouldNotHandleGoesToTheNextNode() {
        List<String> asked = new ArrayList<>();
        NodeMembershipRequester membership = new NodeMembershipRequester(directory, (nodeUrl, method, path, body) -> {
            asked.add(nodeUrl);
            if (nodeUrl.equals(A)) {
                throw new IllegalStateException("down");
            }
            return nodeUrl.equals(B) ? new MembershipResultDto(false, "이 노드는 리더 층 Raft에 참여하고 있지 않다")
                    : new MembershipResultDto(false, "http://10.0.0.9:9001 는 노드 공유 비밀값을 모른다");
        });
        MembershipResultDto r = membership.add("http://10.0.0.9:9001");
        assertFalse(r.accepted());
        assertTrue(r.message().contains("비밀값"), "리더의 거부 이유를 그대로 돌려준다");
        assertEquals(List.of(A, B, C), asked);
    }
}
