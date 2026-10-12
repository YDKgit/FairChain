package com.fairchain.server.admin.service;

import com.fairchain.api.dto.ApiKeyDto;
import com.fairchain.api.dto.RecordRequestDto;
import com.fairchain.api.dto.RecordType;
import com.fairchain.api.dto.SeatIssueRequestDto;
import com.fairchain.server.admin.entity.Clients;
import com.fairchain.server.admin.repository.ClientsRepository;
import com.fairchain.server.admin.repository.OperatorAccountRepository;
import com.fairchain.server.common.ErrorCode;
import com.fairchain.server.common.FairChainException;
import com.fairchain.server.reservation.service.ReservationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {

    private final ClientsRepository clientsRepository;
    private final OperatorAccountRepository operatorAccountRepository;
    private final ReservationService reservationService;

    public AdminService(ClientsRepository clientsRepository, OperatorAccountRepository operatorAccountRepository,
                        ReservationService reservationService) {
        this.clientsRepository = clientsRepository;
        this.operatorAccountRepository = operatorAccountRepository;
        this.reservationService = reservationService;
    }

    /** SD-603: API Key와 대시보드 토큰 */
    @Transactional
    public ApiKeyDto getKeys(String clientId) {
        Clients client = clientsRepository.findById(clientId)
                .orElseThrow(() -> new FairChainException(ErrorCode.NOT_FOUND, "고객사가 없습니다"));
        String dashboardToken = operatorAccountRepository.findFirstByClientIdOrderByIdAsc(clientId)
                .map(account -> account.issueDashboardToken())
                .orElse(null);
        return new ApiKeyDto(client.getApiKey(), dashboardToken);
    }

    /** SD-604: 좌석마다 "발행" 기록 (registerRecord(ISSUE) 공용) */
    public void issueSeats(String clientId, SeatIssueRequestDto dto) {
        for (RecordRequestDto record : dto.records()) {
            if (record.type() != RecordType.ISSUE || !dto.eventId().equals(record.eventId())) {
                throw new FairChainException(ErrorCode.BAD_REQUEST, "좌석 등록은 같은 공연의 ISSUE 기록만 가능합니다");
            }
            reservationService.registerRecord(clientId, record);
        }
    }
}
