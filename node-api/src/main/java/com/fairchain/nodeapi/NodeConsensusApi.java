package com.fairchain.nodeapi;

import com.fairchain.nodeapi.dto.BlockHashDto;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.MemberJoinDto;
import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.nodeapi.dto.RestoreRequestDto;
import com.fairchain.nodeapi.dto.RestoreResultDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 노드 ↔ 노드: 확정 블록 내려받기 — NodeController가 구현.
 *
 * 합의 자체는 Raft 메시지({@link NodeRaftApi})로 한다. 여기 있는 것은 Raft 로그 밖에서 블록을 옮기는 길이다
 * (블록 체인이 스냅샷 역할 — Fabric 방식, 미정사항 16번): 새 노드 · 복구하는 노드 · 팀원이 블록을 받고,
 * 판정할 때 갈라진 번호를 이진 탐색한다.
 */
public interface NodeConsensusApi {

    String BLOCKS = "/node/consensus/blocks";
    String HASH = "/node/consensus/hash";
    String KEYS = "/node/consensus/keys";
    String RESTORE = "/node/consensus/restore";

    /** 확정된 블록을 fromIndex번부터 번호 순으로 (서명 포함). 제외된 노드 · 체인이 깨진 노드는 빈 목록 */
    @GetMapping(BLOCKS)
    List<BlockProposalDto> blocks(@RequestParam("from") Long fromIndex,
                                  @RequestParam(value = "limit", defaultValue = "200") int limit);

    /** index번 블록의 해시와 이 노드의 마지막 번호 — 이진 탐색용 */
    @GetMapping(HASH)
    BlockHashDto blockHash(@RequestParam("index") Long index);

    /**
     * 공개키 등록: 이미 Raft 멤버인 노드가 자기 공개키를 멤버 명단(Raft 로그)에 올린다. 그룹을 처음 만든 멤버는 켜진 뒤 스스로
     * 요청한다 (Fabric의 genesis 블록에 든 orderer 인증서에 해당). 리더가 받고, 다른 노드는 리더에게 넘긴다.
     * 이미 다른 키가 등록돼 있으면 거부한다 — 키 교체는 해지 후 새 키로 재가입 (미정사항 9번).
     */
    @PostMapping(KEYS)
    MembershipResultDto registerKey(@RequestBody MemberJoinDto member);

    /**
     * 복귀 요청 (미정사항 11번): 제외된 노드가 #k부터 지우고 서명 · 해시를 확인하며 다시 받은 뒤 보낸다. 리더가 받고(다른 노드는
     * 넘긴다), 자기 체인과 같으면 Raft 로그에 복귀를 기록한다.
     */
    @PostMapping(RESTORE)
    RestoreResultDto restore(@RequestBody RestoreRequestDto request);
}
