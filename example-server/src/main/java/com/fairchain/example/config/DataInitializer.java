package com.fairchain.example.config;

import com.fairchain.example.entity.Concert;
import com.fairchain.example.entity.GateStaff;
import com.fairchain.example.entity.Operator;
import com.fairchain.example.entity.SeatGrade;
import com.fairchain.example.repository.ConcertRepository;
import com.fairchain.example.repository.GateStaffRepository;
import com.fairchain.example.repository.OperatorRepository;
import com.fairchain.example.repository.SeatGradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 개발용 시드 (dev · fairchain-mock) — 테이블이 비어 있을 때만 넣는다.
 * 좌석은 넣지 않는다 → 운영자 API(POST /admin/seats)로 등록해 ISSUE 흐름까지 확인한다.
 */
@Component
@Profile({"dev", "fairchain-mock"})
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final OperatorRepository operatorRepository;
    private final GateStaffRepository gateStaffRepository;
    private final ConcertRepository concertRepository;
    private final SeatGradeRepository seatGradeRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public DataInitializer(OperatorRepository operatorRepository, GateStaffRepository gateStaffRepository,
                           ConcertRepository concertRepository, SeatGradeRepository seatGradeRepository,
                           PasswordEncoder passwordEncoder, Clock clock) {
        this.operatorRepository = operatorRepository;
        this.gateStaffRepository = gateStaffRepository;
        this.concertRepository = concertRepository;
        this.seatGradeRepository = seatGradeRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (operatorRepository.count() == 0) {
            operatorRepository.save(new Operator("admin", passwordEncoder.encode("admin1234"), "운영자"));
            log.info("[시드] 운영자 admin / admin1234");
        }
        if (gateStaffRepository.count() == 0) {
            gateStaffRepository.save(new GateStaff("staff", passwordEncoder.encode("staff1234"), "게이트 스태프"));
            log.info("[시드] 게이트 스태프 staff / staff1234");
        }
        if (concertRepository.count() == 0) {
            LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
            // 이미 예매 오픈 + 공연 기간이 지금을 포함 → 체크인 시연 가능
            addConcert(new Concert("FairChain 오픈 콘서트", now.minusDays(1), now.minusHours(1), now.plusDays(1), "서울 올림픽홀"));
            // 미래 오픈 → 대기열 진입 403 확인용
            addConcert(new Concert("FairChain 연말 콘서트", now.plusDays(7), now.plusDays(14), now.plusDays(14).plusHours(3), "부산 벡스코"));
            log.info("[시드] 콘서트 2개 (등급 VIP · R)");
        }
    }

    private void addConcert(Concert concert) {
        Concert saved = concertRepository.save(concert);
        seatGradeRepository.save(new SeatGrade(saved.getId(), "VIP", new BigDecimal("150000")));
        seatGradeRepository.save(new SeatGrade(saved.getId(), "R", new BigDecimal("110000")));
    }
}
