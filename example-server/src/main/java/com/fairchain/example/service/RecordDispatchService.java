package com.fairchain.example.service;

import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.example.config.ExampleProperties;
import com.fairchain.example.entity.RecordOutbox;
import com.fairchain.example.repository.RecordOutboxRepository;
import com.fairchain.example.requester.ReservationAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/** C-0308 기록 전송 — Outbox의 PENDING 기록을 주기적으로 FairChain에 보낸다 */
@Service
public class RecordDispatchService {

    private static final Logger log = LoggerFactory.getLogger(RecordDispatchService.class);

    private final ReservationAccess reservationAccess;
    private final ReservationService reservationService;
    private final RecordOutboxRepository outboxRepository;
    private final ExampleProperties properties;
    private final Clock clock;

    public RecordDispatchService(ReservationAccess reservationAccess, ReservationService reservationService,
                                 RecordOutboxRepository outboxRepository, ExampleProperties properties, Clock clock) {
        this.reservationAccess = reservationAccess;
        this.reservationService = reservationService;
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 동기 전송(취소 · 체크인)과 겹치지 않게 생성 후 pending-delay가 지난 것만 */
    @Scheduled(fixedDelayString = "${example.outbox.dispatch-interval-ms}")
    public void dispatchPending() {
        LocalDateTime createdBefore = LocalDateTime.now(clock).minus(properties.outbox().pendingDelay());
        for (RecordOutbox outbox : outboxRepository.findPending(createdBefore)) {
            dispatch(outbox);
        }
    }

    /** 성공하면 SENT(취소는 soldOut 분기까지 끝낸 뒤), 실패하면 retryCount + 1 하고 PENDING 유지 */
    private void dispatch(RecordOutbox outbox) {
        RecordRequestDto record = outbox.getPayload();
        try {
            RecordResponseDto response = reservationAccess.registerRecord(record);
            if (record.type() == RecordType.CANCEL) {
                reservationService.handleCancelResult(record.eventId(), Long.valueOf(record.slotKey()), response.soldOut());
            }
            outbox.markSent();
            outboxRepository.save(outbox);
        } catch (RuntimeException e) {
            outbox.increaseRetry();
            outboxRepository.save(outbox);
            log.warn("기록 재전송 실패 outboxId={} type={} retry={}: {}",
                    outbox.getId(), record.type(), outbox.getRetryCount(), e.getMessage());
        }
    }
}
