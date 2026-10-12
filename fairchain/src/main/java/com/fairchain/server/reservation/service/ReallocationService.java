package com.fairchain.server.reservation.service;

import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.server.reservation.entity.Admission;
import com.fairchain.server.reservation.repository.AdmissionRepository;
import com.fairchain.server.reservation.repository.ReallocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SD-304 재배정 명단. FairChain은 공정한 명단만 만들고, 알림 · 기한 · 순서 진행은 예제서버가 한다.
 */
@Service
public class ReallocationService {

    private final AdmissionRepository admissionRepository;
    private final ReallocationRepository reallocationRepository;

    public ReallocationService(AdmissionRepository admissionRepository, ReallocationRepository reallocationRepository) {
        this.admissionRepository = admissionRepository;
        this.reallocationRepository = reallocationRepository;
    }

    /** ① 최초 매진 순간: 입장했지만 못 산 사람을 원래 순번순으로 */
    @Transactional(readOnly = true)
    public void buildCandidateList(Long eventId) {
        List<Long> userIds = admissionRepository.findNotPurchased(eventId).stream()
                .map(Admission::getUserId)
                .toList();
        reallocationRepository.saveCandidates(eventId, userIds);
    }

    /** ② 매진 후 취소가 나면 예제서버가 가져감 */
    public ReallocationListDto getCandidates(Long eventId) {
        return new ReallocationListDto(eventId, reallocationRepository.findCandidates(eventId));
    }
}
