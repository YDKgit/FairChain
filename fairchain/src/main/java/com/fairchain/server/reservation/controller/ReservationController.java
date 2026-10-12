package com.fairchain.server.reservation.controller;

import com.fairchain.api.ReservationApi;
import com.fairchain.api.dto.AdmissionVerifyDto;
import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordResponseDto;
import com.fairchain.api.dto.VerifyResultDto;
import com.fairchain.server.common.ClientContext;
import com.fairchain.server.queue.service.FC_QueueService;
import com.fairchain.server.reservation.service.ReallocationService;
import com.fairchain.server.reservation.service.ReservationService;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController implements ReservationApi {

    private final ReservationService reservationService;
    private final ReallocationService reallocationService;
    private final FC_QueueService queueService;

    public ReservationController(ReservationService reservationService, ReallocationService reallocationService,
                                 FC_QueueService queueService) {
        this.reservationService = reservationService;
        this.reallocationService = reallocationService;
        this.queueService = queueService;
    }

    @Override
    public VerifyResultDto verifyAdmission(AdmissionVerifyDto dto) {
        return reservationService.verifyAdmission(ClientContext.clientId(), dto);
    }

    @Override
    public RecordResponseDto registerRecord(RecordRequestDto dto) {
        return reservationService.registerRecord(ClientContext.clientId(), dto);
    }

    @Override
    public ReallocationListDto getReallocationCandidates(Long eventId) {
        queueService.checkOwner(ClientContext.clientId(), eventId);
        return reallocationService.getCandidates(eventId);
    }
}
