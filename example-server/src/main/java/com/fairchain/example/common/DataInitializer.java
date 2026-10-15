package com.fairchain.example.common;

import com.fairchain.example.checkin.GateStaff;
import com.fairchain.example.checkin.GateStaffRepository;
import com.fairchain.example.concert.Concert;
import com.fairchain.example.concert.ConcertRepository;
import com.fairchain.example.concert.SeatGrade;
import com.fairchain.example.concert.SeatGradeRepository;
import com.fairchain.example.operator.Operator;
import com.fairchain.example.operator.OperatorRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 개발용 초기 데이터: 운영자 · 게이트 스태프 계정, 더미 공연 3개 (이미 있으면 건너뜀).
 * 좌석은 FairChain에 발행 기록이 필요하므로 운영자 화면의 "좌석 사전 등록"으로 만든다.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private final ExampleProperties properties;
    private final OperatorRepository operatorRepository;
    private final GateStaffRepository staffRepository;
    private final ConcertRepository concertRepository;
    private final SeatGradeRepository seatGradeRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public DataInitializer(ExampleProperties properties, OperatorRepository operatorRepository,
                           GateStaffRepository staffRepository, ConcertRepository concertRepository,
                           SeatGradeRepository seatGradeRepository, BCryptPasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.operatorRepository = operatorRepository;
        this.staffRepository = staffRepository;
        this.concertRepository = concertRepository;
        this.seatGradeRepository = seatGradeRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ExampleProperties.Seed seed = properties.seed();
        if (seed == null) {
            return;
        }
        if (present(seed.operatorLoginId()) && present(seed.operatorPassword())
                && operatorRepository.findByLoginId(seed.operatorLoginId()).isEmpty()) {
            operatorRepository.save(new Operator(seed.operatorLoginId(), passwordEncoder.encode(seed.operatorPassword()), "운영자"));
        }
        if (present(seed.staffLoginId()) && present(seed.staffPassword())
                && staffRepository.findByLoginId(seed.staffLoginId()).isEmpty()) {
            staffRepository.save(new GateStaff(seed.staffLoginId(), passwordEncoder.encode(seed.staffPassword()), "게이트 스태프"));
        }
        if (seed.concerts() && concertRepository.count() == 0) {
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
            // 오늘 공연 — 예매 · 입장(체크인)까지 바로 시연할 수 있도록
            concert("2026 FAIRCHAIN LIVE TOUR - 서울", "서울 · 잠실실내체육관",
                    now.minusDays(1), now.plusHours(2), now.plusHours(5), "VIP", 165000, "R", 132000);
            concert("2026 SUMMER NIGHT CONCERT", "부산 · BEXCO 제1전시장",
                    now.minusDays(1), now.plusDays(30), now.plusDays(30).plusHours(3), "R", 143000, "S", 88000);
            // 아직 예매 오픈 전 — 대기열 진입이 거절되는 것을 볼 수 있다
            concert("MY CENTURY LIVE 2026", "서울 · 올림픽공원 올림픽홀",
                    now.plusDays(7), now.plusDays(40), now.plusDays(40).plusHours(3), "R", 132000, "S", 77000);
        }
    }

    private void concert(String title, String venue, LocalDateTime open, LocalDateTime start, LocalDateTime end,
                         String grade1, int price1, String grade2, int price2) {
        Concert concert = concertRepository.save(new Concert(title, open, start, end, venue));
        seatGradeRepository.save(new SeatGrade(concert.getId(), grade1, BigDecimal.valueOf(price1)));
        seatGradeRepository.save(new SeatGrade(concert.getId(), grade2, BigDecimal.valueOf(price2)));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
