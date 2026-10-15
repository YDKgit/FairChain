package com.fairchain.example.reservation;

import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.RecordType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashSet;
import java.util.Set;

/**
 * Outbox에 쌓인 기록을 FairChain에 보낸다 (C-0308).
 * 같은 좌석의 기록은 버전 순서가 중요하므로, 앞선 기록이 안 갔으면 그 좌석의 뒤 기록은 기다린다.
 */
@Service
public class RecordDispatchService {

    private static final Logger log = LoggerFactory.getLogger(RecordDispatchService.class);

    private final RecordOutboxRepository outboxRepository;
    private final ReservationAccess reservationAccess;
    private final ReallocationService reallocationService;
    private final ObjectMapper objectMapper;

    public RecordDispatchService(RecordOutboxRepository outboxRepository, ReservationAccess reservationAccess,
                                 ReallocationService reallocationService, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.reservationAccess = reservationAccess;
        this.reallocationService = reallocationService;
        this.objectMapper = objectMapper;
    }

    /** 예매 트랜잭션 안에서 호출 — 실제 전송은 커밋 뒤 dispatchPending */
    public void enqueue(Long seatId, RecordRequestDto record) {
        try {
            outboxRepository.save(new RecordOutbox(seatId, record.type(), objectMapper.writeValueAsString(record)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 2000)
    public synchronized void dispatchPending() {
        Set<Long> waitingSeats = new HashSet<>();
        for (RecordOutbox outbox : outboxRepository.findPending()) {
            if (waitingSeats.contains(outbox.getSeatId())) {
                continue;
            }
            RecordRequestDto record = read(outbox.getPayload());
            try {
                RecordResponseDto response = reservationAccess.registerRecord(record);
                outbox.sent(response != null && response.soldOut());
                outboxRepository.save(outbox);
                if (record.type() == RecordType.CANCEL) {
                    reallocationService.afterCancel(record.eventId(), outbox.getSeatId(), Boolean.TRUE.equals(outbox.getSoldOut()));
                }
            } catch (ResourceAccessException e) {
                // FairChain이 응답하지 않음 — 이번 주기는 여기까지, 다음 주기에 순서대로 다시
                outbox.retryLater(e.getMessage());
                outboxRepository.save(outbox);
                return;
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().is4xxClientError() && e.getStatusCode().value() != HttpStatus.TOO_MANY_REQUESTS.value()) {
                    outbox.failed(e.getResponseBodyAsString());
                    log.error("FairChain이 기록을 거절함 ({} {} v{}): {}", record.type(), outbox.getSeatId(),
                            record.version(), e.getResponseBodyAsString());
                } else {
                    outbox.retryLater(e.getMessage());
                    waitingSeats.add(outbox.getSeatId());
                }
                outboxRepository.save(outbox);
            }
        }
    }

    private RecordRequestDto read(String payload) {
        try {
            return objectMapper.readValue(payload, RecordRequestDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
