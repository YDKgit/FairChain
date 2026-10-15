package com.fairchain.example.ticket;

import com.fairchain.api.dto.BlockState;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.VerifiedAnchorDto;
import com.fairchain.example.common.TicketFingerprint;
import com.fairchain.example.reservation.OutboxStatus;
import com.fairchain.example.reservation.RecordOutboxRepository;
import com.fairchain.example.reservation.Ticket;
import com.fairchain.example.reservation.TicketStatus;
import org.springframework.stereotype.Service;

/** 티켓마다 키 · 지문을 다시 계산해 FairChain 기록(노드 대조 결과 포함)과 비교 (C-0509) */
@Service
public class TicketVerificationService {

    private final TicketFingerprint fingerprint;
    private final RecordOutboxRepository outboxRepository;

    public TicketVerificationService(TicketFingerprint fingerprint, RecordOutboxRepository outboxRepository) {
        this.fingerprint = fingerprint;
        this.outboxRepository = outboxRepository;
    }

    /**
     * @param anchor                이 티켓 좌석 키의 FairChain 최신 기록 (없으면 null)
     * @param verificationAvailable FairChain · 노드가 응답했는지
     */
    public VerificationStatus determineStatus(Ticket ticket, VerifiedAnchorDto anchor, boolean verificationAvailable) {
        if (!verificationAvailable) {
            return VerificationStatus.UNAVAILABLE;
        }
        if (outboxRepository.existsBySeatIdAndStatus(ticket.getSeatId(), OutboxStatus.PENDING)) {
            return VerificationStatus.PENDING;         // 아직 FairChain에 보내지 못한 기록이 있음
        }
        if (anchor == null) {
            return VerificationStatus.UNREGISTERED_LOCAL;
        }
        if (anchor.blockState() == BlockState.PENDING) {
            return VerificationStatus.PENDING;         // FairChain에는 있고 블록 확정을 기다리는 중
        }
        RecordType type = ticket.getStatus() == TicketStatus.CHECKED_IN ? RecordType.CHECKIN : RecordType.PURCHASE;
        String expected = fingerprint.recordHash(ticket.getConcertId(), ticket.getSeatId(), ticket.getMemberId(),
                TicketFingerprint.statusOf(type), ticket.getVersion());
        if (!ticket.getVersion().equals(anchor.version()) || !expected.equals(anchor.recordHash())) {
            return VerificationStatus.TAMPER_SUSPECTED;
        }
        return anchor.nodeMatched() ? VerificationStatus.VERIFIED : VerificationStatus.TAMPER_SUSPECTED;
    }
}
