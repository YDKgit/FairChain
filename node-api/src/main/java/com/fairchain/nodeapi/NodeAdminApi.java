package com.fairchain.nodeapi;

import com.fairchain.nodeapi.dto.MemberJoinDto;
import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 노드 관리 API (미정사항 15번) — NodeController가 구현.
 *
 * 가입 · 해지는 FairChain 운영자가 점검 화면(노드 추가 · 해지)이나 스크립트로 요청한다. 아무 노드에나 보내면 리더에게 넘어간다.
 * 블록체인은 FairChain이 운영하는 것으로 정했기 때문이다 (11/12 답변 15번 "FairChain 화면에서는 호출하지 않는다"를 바꿈).
 * 가입할 노드는 주소만 알려 주면 되고, 리더가 그 노드에게 직접 확인한다 ({@link NodeJoinApi}).
 */
public interface NodeAdminApi {

    String MEMBERS = "/node/admin/members";
    String MEMBER = "/node/admin/members/{id}";
    String STATUS = "/node/admin/status";
    String RESUME = "/node/admin/resume";

    /** 가입: 그 주소의 노드를 확인한 뒤 learner로 붙여 따라잡게 하고 투표 멤버로 올린다. 한 번에 하나씩 */
    @PostMapping(MEMBERS)
    MembershipResultDto addMember(@RequestBody MemberJoinDto member);

    /** 해지: 멤버 명단에서 뺀다. 남는 팀이 3개 미만이 되면 거부 */
    @DeleteMapping(MEMBER)
    MembershipResultDto removeMember(@PathVariable("id") String id);

    @GetMapping(STATUS)
    NodeStatusDto status();

    /**
     * 기록 재개: 서명이 둘 다 맞는 서로 다른 블록(키 유출 의심)이 나와 멈춘 기록을 관리자가 확인한 뒤 다시 연다
     * (11/06 변경안 6장 "기록 중단 + 관리자 확인").
     */
    @PostMapping(RESUME)
    MembershipResultDto resume();

    static String memberPath(String id) {
        return MEMBER.replace("{id}", id);
    }
}
