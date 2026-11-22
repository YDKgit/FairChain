package com.fairchain.example.common;

import com.fairchain.example.checkin.GateStaff;
import com.fairchain.example.checkin.GateStaffRepository;
import com.fairchain.example.concert.Concert;
import com.fairchain.example.concert.ConcertRepository;
import com.fairchain.example.concert.SeatGrade;
import com.fairchain.example.concert.SeatGradeRepository;
import com.fairchain.example.member.Member;
import com.fairchain.example.member.MemberRepository;
import com.fairchain.example.operator.Operator;
import com.fairchain.example.operator.OperatorRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 개발용 초기 데이터: 운영자 · 게이트 스태프 계정, 시연 회원 5명, 더미 공연 (이미 있으면 건너뜀).
 * 좌석은 FairChain에 발행 기록이 필요하므로 운영자 화면의 "좌석 사전 등록"으로 만든다.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    /** 시연 회원 — 로그인 화면의 "시연 계정" 버튼에 나온다 (아이디, 이름) */
    public static final List<String[]> DEMO_MEMBERS = List.of(
            new String[] {"chulsoo", "김철수"},
            new String[] {"younghee", "이영희"},
            new String[] {"minsu", "박민수"},
            new String[] {"jiwoo", "최지우"},
            new String[] {"haneul", "정하늘"});

    private final ExampleProperties properties;
    private final OperatorRepository operatorRepository;
    private final GateStaffRepository staffRepository;
    private final MemberRepository memberRepository;
    private final ConcertRepository concertRepository;
    private final SeatGradeRepository seatGradeRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public DataInitializer(ExampleProperties properties, OperatorRepository operatorRepository,
                           GateStaffRepository staffRepository, MemberRepository memberRepository,
                           ConcertRepository concertRepository, SeatGradeRepository seatGradeRepository,
                           BCryptPasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.operatorRepository = operatorRepository;
        this.staffRepository = staffRepository;
        this.memberRepository = memberRepository;
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
        if (!Credentials.isBlank(seed.operatorLoginId()) && !Credentials.isBlank(seed.operatorPassword())
                && operatorRepository.findByLoginId(seed.operatorLoginId()).isEmpty()) {
            operatorRepository.save(new Operator(seed.operatorLoginId(), passwordEncoder.encode(seed.operatorPassword()), "운영자"));
        }
        if (!Credentials.isBlank(seed.staffLoginId()) && !Credentials.isBlank(seed.staffPassword())
                && staffRepository.findByLoginId(seed.staffLoginId()).isEmpty()) {
            staffRepository.save(new GateStaff(seed.staffLoginId(), passwordEncoder.encode(seed.staffPassword()), "게이트 스태프"));
        }
        if (!Credentials.isBlank(seed.memberPassword())) {
            String hash = passwordEncoder.encode(seed.memberPassword());
            for (String[] demo : DEMO_MEMBERS) {
                if (!memberRepository.existsByLoginId(demo[0])) {
                    memberRepository.save(new Member(demo[0], hash, demo[1], "010-0000-0000"));
                }
            }
        }
        if (seed.loadMembers() > 0 && !memberRepository.existsByLoginId("load1")) {
            // 부하 테스트용 회원을 미리 만든다. 가입 · 로그인마다 드는 비밀번호 해시 시간이 측정을 가리지 않게
            // 가장 가벼운 해시 강도(4)로 한 번만 계산해 모두에게 쓴다 — 로그인 검사 시간은 저장된 해시의 강도를 따른다
            String hash = new BCryptPasswordEncoder(4).encode("load1234");
            List<Member> batch = new ArrayList<>();
            for (int i = 1; i <= seed.loadMembers(); i++) {
                batch.add(new Member("load" + i, hash, "부하" + i, "010-0000-0000"));
            }
            memberRepository.saveAll(batch);
        }
        if (seed.concerts() && concertRepository.count() == 0) {
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
            // 오늘 공연 — 예매 · 입장(체크인)까지 바로 시연할 수 있도록 (공연 임박이라 취소표는 바로 판매)
            concert("2026 FAIRCHAIN LIVE TOUR - 서울", "서울 · 잠실실내체육관",
                    now.minusDays(1), now.plusHours(2), now.plusHours(5), "VIP", 165000, "R", 132000);
            // 한 달 뒤 공연 — 매진 후 취소표 재배정(20시 제안 · 다음 날 9시 일반 판매)을 볼 수 있다
            concert("2026 SUMMER NIGHT CONCERT", "부산 · BEXCO 제1전시장",
                    now.minusDays(1), now.plusDays(30), now.plusDays(30).plusHours(3), "R", 143000, "S", 88000);
            // 아직 예매 오픈 전 — 대기열 진입이 거절되는 것을 볼 수 있다
            concert("MY CENTURY LIVE 2026", "서울 · 올림픽공원 올림픽홀",
                    now.plusDays(7), now.plusDays(40), now.plusDays(40).plusHours(3), "R", 132000, "S", 77000);

            // 사이트가 실제 예매처처럼 보이도록 장르가 다른 공연들 (화면용 소개는 frontend/src/catalog.js — 제목이 같아야 한다)
            LocalDateTime day = now.toLocalDate().atStartOfDay();
            concert("루나베일 단독 콘서트 〈MOONPHASE〉", "서울 · KSPO DOME",
                    now.minusDays(2), day.plusDays(21).plusHours(18), day.plusDays(21).plusHours(21), "VIP", 176000, "R", 143000);
            concert("한결 전국투어 〈겨울의 온도〉 - 대구", "대구 · 엑스코 오디토리움",
                    now.minusDays(3), day.plusDays(14).plusHours(17), day.plusDays(14).plusHours(19), "R", 121000, "S", 99000);
            concert("NEON HARBOR ROCK FESTIVAL 2026", "인천 · 송도달빛축제공원",
                    now.minusDays(5), day.plusDays(45).plusHours(13), day.plusDays(45).plusHours(22), "R", 154000, "S", 110000);
            concert("블랙파인 〈ASPHALT〉 앙코르", "서울 · 올림픽공원 핸드볼경기장",
                    now.minusDays(1), day.plusDays(10).plusHours(19), day.plusDays(10).plusHours(21), "R", 132000, "S", 110000);
            concert("새벽세시 어쿠스틱 라이브 〈잔향〉", "광주 · 빛고을시민문화관",
                    now.minusDays(4), day.plusDays(8).plusHours(19), day.plusDays(8).plusHours(21), "R", 66000, "S", 55000);
            concert("아르떼 심포니 송년음악회", "서울 · 예술의전당 콘서트홀",
                    day.plusDays(3).plusHours(14), day.plusDays(38).plusHours(19), day.plusDays(38).plusHours(21), "R", 90000, "S", 60000);
            concert("HALCYON DRIVE WORLD TOUR in SEOUL", "서울 · 고척스카이돔",
                    day.plusDays(5).plusHours(20), day.plusDays(60).plusHours(19), day.plusDays(60).plusHours(21), "VIP", 198000, "R", 154000);
        }
    }

    private void concert(String title, String venue, LocalDateTime open, LocalDateTime start, LocalDateTime end,
                         String grade1, int price1, String grade2, int price2) {
        Concert concert = concertRepository.save(new Concert(title, open, start, end, venue));
        seatGradeRepository.save(new SeatGrade(concert.getId(), grade1, BigDecimal.valueOf(price1)));
        seatGradeRepository.save(new SeatGrade(concert.getId(), grade2, BigDecimal.valueOf(price2)));
    }
}
