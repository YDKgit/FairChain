package com.fairchain.server.verify.service;

import com.fairchain.api.dto.AnchorListRequestDto;
import com.fairchain.api.dto.AnchorListResponseDto;
import com.fairchain.api.dto.BlockState;
import com.fairchain.api.dto.VerifiedAnchorDto;
import com.fairchain.api.dto.VerifyRequestDto;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.api.dto.VerifyStatus;
import com.fairchain.nodeapi.dto.BlockBatchVerifyRequestDto;
import com.fairchain.nodeapi.dto.BlockBatchVerifyResponseDto;
import com.fairchain.nodeapi.dto.BlockVerifyDto;
import com.fairchain.nodeapi.dto.BlockVerifyItemDto;
import com.fairchain.nodeapi.dto.BlockVerifyItemResultDto;
import com.fairchain.nodeapi.dto.BlockVerifyResultDto;
import com.fairchain.server.block.entity.AnchorRecord;
import com.fairchain.server.block.repository.AnchorRecordRepository;
import com.fairchain.server.block.requester.BlockRecordAccess;
import com.fairchain.server.block.requester.NodeVerificationAccess;
import com.fairchain.server.common.FairChainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** SS-04 체크인 단건 검증 + SS-05 목록 검증 */
@Service
public class VerifyService {

    private final BlockRecordAccess blockRecordAccess;
    private final NodeVerificationAccess nodeVerificationAccess;
    private final AnchorRecordRepository anchorRecordRepository;

    public VerifyService(BlockRecordAccess blockRecordAccess, NodeVerificationAccess nodeVerificationAccess,
                         AnchorRecordRepository anchorRecordRepository) {
        this.blockRecordAccess = blockRecordAccess;
        this.nodeVerificationAccess = nodeVerificationAccess;
        this.anchorRecordRepository = anchorRecordRepository;
    }

    /**
     * SD-401-02: 들고 온 티켓의 지문을 노드 3대 최신 지문(2/3)과 대조.
     * 노드에 아직 그 버전이 없으면(블록 확정 전) FairChain 사본으로 확인.
     */
    @Transactional(readOnly = true)
    public VerifyResultDto verify(String clientId, VerifyRequestDto dto) {
        Optional<AnchorRecord> latestCopy = anchorRecordRepository.findLatest(dto.recordKey());
        if (latestCopy.isEmpty() || !latestCopy.get().getClientId().equals(clientId)) {
            return new VerifyResultDto(VerifyStatus.TAMPERED, "발급 기록이 없는 티켓입니다");
        }

        BlockVerifyResultDto node;
        try {
            node = blockRecordAccess.verifyRecord(new BlockVerifyDto(dto.recordKey(), dto.version(), dto.recordHash()));
        } catch (FairChainException e) {
            return new VerifyResultDto(VerifyStatus.UNKNOWN, "노드 응답이 부족해 검증할 수 없습니다");
        }

        if (node.latestVersion() == null || node.latestVersion() < dto.version()) {
            return compareWithCopy(latestCopy.get(), dto);
        }
        if (node.valid()) {
            return new VerifyResultDto(VerifyStatus.VERIFIED, null);
        }
        if (node.latestVersion() > dto.version()) {
            return new VerifyResultDto(VerifyStatus.TAMPERED,
                    "최신 티켓이 아닙니다 (블록체인 최신 v" + node.latestVersion() + ")");
        }
        return new VerifyResultDto(VerifyStatus.TAMPERED, "티켓 내용이 블록체인 기록과 다릅니다");
    }

    /** 블록 확정 전: 사본의 최신 기록이 PENDING이고 버전 · 지문이 같아야 통과 */
    private VerifyResultDto compareWithCopy(AnchorRecord copy, VerifyRequestDto dto) {
        boolean same = copy.getVersion().equals(dto.version()) && copy.getRecordHash().equals(dto.recordHash());
        if (same && copy.getBlockState() == BlockState.PENDING) {
            return new VerifyResultDto(VerifyStatus.VERIFIED, "블록 기록 중 (사본 기준)");
        }
        return new VerifyResultDto(VerifyStatus.TAMPERED, "티켓 내용이 발급 기록과 다릅니다");
    }

    /**
     * SD-501-02: 회원의 최신 기록 사본 → 확정된 것만 블록 번호 묶음으로 노드 대조.
     * 노드가 답하지 않으면 사본 기준으로만 반환 (verificationAvailable = false).
     */
    @Transactional(readOnly = true)
    public AnchorListResponseDto getVerifiedAnchors(String clientId, AnchorListRequestDto dto) {
        List<AnchorRecord> records = anchorRecordRepository.findLatestOwned(clientId, dto.userId(), dto.eventId());

        List<BlockVerifyItemDto> confirmed = records.stream()
                .filter(AnchorRecord::isConfirmed)
                .map(r -> new BlockVerifyItemDto(r.getBlockIndex(), r.getRecordKey(), r.getVersion(), r.getRecordHash()))
                .toList();

        Map<Long, BlockVerifyItemResultDto> nodeResults = Map.of();
        boolean available = true;
        if (!confirmed.isEmpty()) {
            BlockBatchVerifyResponseDto response = nodeVerificationAccess.verifyBatch(new BlockBatchVerifyRequestDto(confirmed));
            nodeResults = response.results().stream()
                    .collect(Collectors.toMap(BlockVerifyItemResultDto::blockIndex, Function.identity(), (a, b) -> a));
            available = nodeResults.size() == confirmed.size();
        }

        Map<Long, BlockVerifyItemResultDto> results = nodeResults;
        List<VerifiedAnchorDto> anchors = records.stream()
                .map(r -> {
                    BlockVerifyItemResultDto result = r.isConfirmed() ? results.get(r.getBlockIndex()) : null;
                    boolean matched = result != null && result.matched() && result.confirmed();
                    return new VerifiedAnchorDto(r.getRecordKey(), r.getVersion(), r.getRecordHash(),
                            r.getBlockIndex(), r.getBlockState(), matched);
                })
                .toList();
        return new AnchorListResponseDto(anchors, available);
    }
}
