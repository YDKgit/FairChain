package com.fairchain.node.controller;

import com.fairchain.node.config.NodeSettings;
import com.fairchain.node.raft.RaftMessageCodec;
import com.fairchain.node.raft.RaftNodeHolder;
import com.fairchain.node.service.DivergenceService;
import com.fairchain.node.service.MembershipService;
import com.fairchain.node.service.NodeService;
import com.fairchain.nodeapi.NodeReportApi;
import com.fairchain.nodeapi.dto.DivergenceReportDto;
import com.fairchain.nodeapi.dto.ReportAckDto;
import com.fairchain.nodeapi.dto.RestoreRequestDto;
import com.fairchain.nodeapi.dto.RestoreResultDto;
import com.fairchain.node.team.MemberReplicaService;
import com.fairchain.node.team.TeamLeaderService;
import com.fairchain.nodeapi.BlockRecordApi;
import com.fairchain.nodeapi.NodeAdminApi;
import com.fairchain.nodeapi.NodeAuditApi;
import com.fairchain.nodeapi.NodeConsensusApi;
import com.fairchain.nodeapi.NodeHeaders;
import com.fairchain.nodeapi.NodeJoinApi;
import com.fairchain.nodeapi.NodeMemberApi;
import com.fairchain.nodeapi.dto.JoinChallengeDto;
import com.fairchain.nodeapi.dto.JoinProofDto;
import com.fairchain.nodeapi.NodeRaftApi;
import com.fairchain.nodeapi.NodeTeamApi;
import com.fairchain.nodeapi.NodeVerificationApi;
import com.fairchain.nodeapi.dto.MemberAckDto;
import com.fairchain.nodeapi.dto.TeamHelloDto;
import org.springframework.web.server.ResponseStatusException;
import com.fairchain.nodeapi.dto.BlockBatchVerifyRequestDto;
import com.fairchain.nodeapi.dto.BlockBatchVerifyResponseDto;
import com.fairchain.nodeapi.dto.BlockDto;
import com.fairchain.nodeapi.dto.BlockHashDto;
import com.fairchain.nodeapi.dto.BlockProofDto;
import com.fairchain.nodeapi.dto.BlockProposalDto;
import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fairchain.nodeapi.dto.BlockResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import com.fairchain.nodeapi.dto.MemberJoinDto;
import com.fairchain.nodeapi.dto.MembershipResultDto;
import com.fairchain.nodeapi.dto.NodeLedgerStatusDto;
import com.fairchain.nodeapi.dto.NodeStatusDto;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 경로 · 매핑은 node-api 인터페이스에 있고, 여기서는 implements만 한다. /node/** 인증은 NodeAuthFilter */
@RestController
public class NodeController implements BlockRecordApi, NodeConsensusApi, NodeVerificationApi, NodeAuditApi,
        NodeRaftApi, NodeAdminApi, NodeTeamApi, NodeMemberApi, NodeReportApi, NodeJoinApi {

    private static final Logger log = LoggerFactory.getLogger(NodeController.class);

    private final NodeService service;
    private final MembershipService membership;
    private final DivergenceService divergence;
    private final TeamLeaderService teamLeader;
    private final MemberReplicaService replica;
    private final NodeSettings settings;
    private final RaftNodeHolder raft;
    private final RaftMessageCodec codec;
    private final HttpServletRequest request;

    public NodeController(NodeService service, MembershipService membership, DivergenceService divergence,
                          TeamLeaderService teamLeader, MemberReplicaService replica, NodeSettings settings,
                          RaftNodeHolder raft, RaftMessageCodec codec, HttpServletRequest request) {
        this.service = service;
        this.membership = membership;
        this.divergence = divergence;
        this.teamLeader = teamLeader;
        this.replica = replica;
        this.settings = settings;
        this.raft = raft;
        this.codec = codec;
        this.request = request;
    }

    // FairChain → 노드
    @Override
    public BlockResultDto submitRecord(BlockRecordDto dto) {
        return service.submit(dto, request.getHeader(NodeHeaders.FORWARDED) != null);
    }

    @Override
    public BlockVerifyResultDto verifyRecord(BlockVerifyDto dto) {
        return service.verify(dto);
    }

    @Override
    public BlockBatchVerifyResponseDto verifyBatch(BlockBatchVerifyRequestDto dto) {
        return service.batch(dto);
    }

    @Override
    public BlockProofDto proof(String recordKey) {
        return service.proof(recordKey);
    }

    @Override
    public NodeLedgerStatusDto ledgerStatus() {
        return service.ledgerStatus();
    }

    @Override
    public List<BlockDto> ledgerBlocks(Long fromIndex, int limit) {
        return service.ledgerBlocks(fromIndex, limit);
    }

    // 노드 ↔ 노드
    @Override
    public List<BlockProposalDto> blocks(Long fromIndex, int limit) {
        return service.blocks(fromIndex, limit);
    }

    @Override
    public BlockHashDto blockHash(Long index) {
        return service.blockHash(index);
    }

    @Override
    public MembershipResultDto registerKey(MemberJoinDto member) {
        return membership.registerKey(member, forwarded());
    }

    @Override
    public RestoreResultDto restore(RestoreRequestDto restoreRequest) {
        return divergence.restore(restoreRequest, forwarded());
    }

    // 신고 (FairChain · 팀 리더 · 노드 자신)
    @Override
    public ReportAckDto report(DivergenceReportDto report) {
        return divergence.report(report, forwarded());
    }

    private boolean forwarded() {
        return request.getHeader(NodeHeaders.FORWARDED) != null;
    }

    // 팀 리더 ↔ 팀원
    @Override
    public MembershipResultDto hello(TeamHelloDto hello) {
        return teamLeader.hello(hello);
    }

    @Override
    public MemberAckDto receiveBlocks(List<BlockProposalDto> blocks) {
        if (settings.leaderLayer()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Leader-layer nodes take blocks from the Raft log, not from a team leader");
        }
        return replica.receive(blocks);
    }

    @Override
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void receiveRaftMessage(String message) {
        try {
            raft.handle(codec.decode(message));
        } catch (RuntimeException e) {
            log.warn("읽을 수 없는 Raft 메시지: {}", e.toString());
            throw new IllegalArgumentException("Bad Raft message");
        }
    }

    // 가입 전 확인 (비밀값 헤더 없이 받는 유일한 경로 — NodeAuthFilter)
    @Override
    public JoinProofDto joinProof(JoinChallengeDto challenge) {
        return membership.proveJoin(challenge);
    }

    // 운영자
    @Override
    public MembershipResultDto addMember(MemberJoinDto member) {
        return membership.addMember(member, forwarded());
    }

    @Override
    public MembershipResultDto removeMember(String id) {
        return membership.removeMember(id, forwarded());
    }

    @Override
    public NodeStatusDto status() {
        return service.status();
    }

    @Override
    public MembershipResultDto resume() {
        return divergence.resume(forwarded());
    }
}
