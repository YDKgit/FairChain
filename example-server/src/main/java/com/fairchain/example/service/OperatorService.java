package com.fairchain.example.service;

import com.fairchain.api.dto.ApiKeyDto;
import com.fairchain.example.common.BadRequestException;
import com.fairchain.example.common.NotFoundException;
import com.fairchain.example.common.UnauthorizedException;
import com.fairchain.example.dto.ConcertRegisterDto;
import com.fairchain.example.dto.LoginRequestDto;
import com.fairchain.example.dto.SeatDto;
import com.fairchain.example.dto.SeatListResponseDto;
import com.fairchain.example.dto.SeatRegisterDto;
import com.fairchain.example.entity.Concert;
import com.fairchain.example.entity.Operator;
import com.fairchain.example.entity.Seat;
import com.fairchain.example.entity.SeatGrade;
import com.fairchain.example.repository.ConcertRepository;
import com.fairchain.example.repository.OperatorRepository;
import com.fairchain.example.repository.SeatGradeRepository;
import com.fairchain.example.repository.SeatRepository;
import com.fairchain.example.requester.AdminAccess;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** C-0604 운영 서비스 — 세션 생성 · 종료는 Controller */
@Service
public class OperatorService {

    private final AdminAccess adminAccess;
    private final OperatorRepository operatorRepository;
    private final ConcertRepository concertRepository;
    private final SeatGradeRepository seatGradeRepository;
    private final SeatRepository seatRepository;
    private final PasswordEncoder passwordEncoder;

    public OperatorService(AdminAccess adminAccess, OperatorRepository operatorRepository,
                           ConcertRepository concertRepository, SeatGradeRepository seatGradeRepository,
                           SeatRepository seatRepository, PasswordEncoder passwordEncoder) {
        this.adminAccess = adminAccess;
        this.operatorRepository = operatorRepository;
        this.concertRepository = concertRepository;
        this.seatGradeRepository = seatGradeRepository;
        this.seatRepository = seatRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public Operator login(LoginRequestDto dto) {
        return operatorRepository.findByLoginId(dto.loginId())
                .filter(operator -> passwordEncoder.matches(dto.password(), operator.getPassword()))
                .orElseThrow(() -> new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다"));
    }

    public ApiKeyDto getApiKey() {
        return adminAccess.getKeys();
    }

    /** SD-604 — 좌석 저장 후 ISSUE 기록. FairChain 전송이 실패하면 전체 롤백(502) */
    @Transactional
    public SeatListResponseDto registerSeats(SeatRegisterDto dto) {
        SeatGrade grade = seatGradeRepository.findById(dto.seatGradeId())
                .filter(found -> found.getConcertId().equals(dto.concertId()))
                .orElseThrow(() -> new BadRequestException("좌석 등급이 이 공연 소속이 아닙니다"));
        List<Seat> seats = seatRepository.saveAllAndFlush(dto.seatNumbers().stream()
                .map(seatNumber -> new Seat(grade.getId(), seatNumber))
                .toList());
        adminAccess.issueSeats(dto.concertId(), seats);
        return new SeatListResponseDto(seats.stream()
                .map(seat -> new SeatDto(seat.getId(), seat.getSeatNumber(), grade.getGradeName(), grade.getPrice(),
                        seat.getStatus()))
                .toList());
    }

    /** 등급(seatGrades)이 있으면 함께 저장 */
    @Transactional
    public Long createConcert(ConcertRegisterDto dto) {
        validatePeriod(dto);
        Concert concert = concertRepository.save(new Concert(dto.title(), dto.eventOpen(), dto.eventStart(),
                dto.eventEnd(), dto.venue()));
        if (dto.seatGrades() != null) {
            seatGradeRepository.saveAll(dto.seatGrades().stream()
                    .map(grade -> new SeatGrade(concert.getId(), grade.gradeName(), grade.price()))
                    .toList());
        }
        return concert.getId();
    }

    /** 공연 정보만 수정 — seatGrades는 무시 */
    @Transactional
    public void updateConcert(Long concertId, ConcertRegisterDto dto) {
        validatePeriod(dto);
        Concert concert = findConcert(concertId);
        concert.update(dto.title(), dto.eventOpen(), dto.eventStart(), dto.eventEnd(), dto.venue());
        concertRepository.save(concert);
    }

    @Transactional
    public void deleteConcert(Long concertId) {
        findConcert(concertId);
        concertRepository.deleteById(concertId);
    }

    private Concert findConcert(Long concertId) {
        return concertRepository.findById(concertId)
                .orElseThrow(() -> new NotFoundException("공연을 찾을 수 없습니다"));
    }

    private void validatePeriod(ConcertRegisterDto dto) {
        if (!dto.eventStart().isBefore(dto.eventEnd())) {
            throw new BadRequestException("공연 시작은 종료보다 앞서야 합니다");
        }
    }
}
