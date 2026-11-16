package com.fairchain.nodeapi;

import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.nodeapi.dto.TeamHelloDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 팀원 → 팀 리더: 등록 겸 상태 알림 — 팀 리더(리더 층 노드)의 NodeController가 구현.
 * 팀원 추가 · 해지는 리더 층 Raft 설정을 건드리지 않는다 (11/12 답변 1 · 2번). 팀원이 켜지면 스스로 등록된다.
 */
public interface NodeTeamApi {

    String MEMBERS = "/node/team/members";

    @PostMapping(MEMBERS)
    MembershipResultDto hello(@RequestBody TeamHelloDto hello);
}
