package com.fairchain.example.operator;

import com.fairchain.api.dto.ApiKeyDto;
import com.fairchain.example.common.ApiException;
import com.fairchain.example.common.ExampleProperties;
import com.fairchain.example.concert.Concert;
import com.fairchain.example.concert.ConcertRepository;
import com.fairchain.example.concert.SeatGrade;
import com.fairchain.example.concert.SeatGradeRepository;
import com.fairchain.example.operator.OperatorDtos.ConcertRegisterDto;
import com.fairchain.example.operator.OperatorDtos.KeysDto;
import com.fairchain.example.operator.OperatorDtos.OperatorLoginDto;
import com.fairchain.example.operator.OperatorDtos.SeatRegisterDto;
import com.fairchain.example.reservation.Seat;
import com.fairchain.example.reservation.SeatRepository;
import com.fairchain.example.reservation.TicketRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SS-06 운영 관리 (C-0604) */
@Service
public class OperatorService {

    private static final Pattern RANGE = Pattern.compile("([A-Za-z]*)(\\d+)\\s*-\\s*\\1?(\\d+)");
    private static final int MAX_SEATS_PER_REQUEST = 200;

    private final OperatorRepository operatorRepository;
    private final ConcertRepository concertRepository;
    private final SeatGradeRepository seatGradeRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final AdminAccess adminAccess;
    private final BCryptPasswordEncoder passwordEncoder;
    private final String fairChainUrl;

    public OperatorService(OperatorRepository operatorRepository, ConcertRepository concertRepository,
                           SeatGradeRepository seatGradeRepository, SeatRepository seatRepository,
                           TicketRepository ticketRepository, AdminAccess adminAccess,
                           BCryptPasswordEncoder passwordEncoder, ExampleProperties properties) {
        this.operatorRepository = operatorRepository;
        this.concertRepository = concertRepository;
        this.seatGradeRepository = seatGradeRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.adminAccess = adminAccess;
        this.passwordEncoder = passwordEncoder;
        this.fairChainUrl = properties.fairchain().url();
    }

    @Transactional(readOnly = true)
    public Operator login(OperatorLoginDto dto) {
        return operatorRepository.findByLoginId(dto == null ? null : dto.loginId())
                .filter(o -> passwordEncoder.matches(dto.password(), o.getPassword()))
                .orElseThrow(() -> ApiException.unauthorized("아이디 또는 비밀번호가 틀렸습니다"));
    }

    /** FairChain에서 apiKey · 대시보드 토큰 조회 */
    public KeysDto getApiKey() {
        ApiKeyDto keys = adminAccess.getKeys();
        return new KeysDto(keys.apiKey(), keys.dashboardToken(), fairChainUrl);
    }

    /** Seat(EMPTY) 저장 후 FairChain에 발행 기록 — FairChain이 거절하면 좌석 저장도 되돌린다 */
    @Transactional
    public List<Seat> registerSeats(SeatRegisterDto dto) {
        if (dto == null || dto.concertId() == null || dto.seatGradeId() == null || dto.seatNumbers() == null) {
            throw ApiException.badRequest("공연 · 좌석 등급 · 좌석 번호는 필수입니다");
        }
        concertRepository.findById(dto.concertId()).orElseThrow(() -> ApiException.notFound("공연이 없습니다"));
        SeatGrade grade = seatGradeRepository.findById(dto.seatGradeId())
                .filter(g -> g.getConcertId().equals(dto.concertId()))
                .orElseThrow(() -> ApiException.badRequest("이 공연의 좌석 등급이 아닙니다"));
        List<String> numbers = expand(dto.seatNumbers());
        if (numbers.isEmpty() || numbers.size() > MAX_SEATS_PER_REQUEST) {
            throw ApiException.badRequest("좌석은 한 번에 1~" + MAX_SEATS_PER_REQUEST + "개까지 등록할 수 있습니다");
        }
        List<Seat> seats = new ArrayList<>();
        for (String number : numbers) {
            if (seatRepository.existsByConcertIdAndSeatNumber(dto.concertId(), number)) {
                throw ApiException.conflict("이미 등록된 좌석입니다: " + number);
            }
            seats.add(seatRepository.save(new Seat(dto.concertId(), grade.getId(), number)));
        }
        seatRepository.flush();
        adminAccess.issueSeats(dto.concertId(), seats);
        return seats;
    }

    @Transactional
    public Concert createConcert(ConcertRegisterDto dto) {
        validate(dto);
        Concert concert = concertRepository.save(new Concert(dto.title().trim(), dto.eventOpen(), dto.eventStart(),
                dto.eventEnd(), dto.venue().trim()));
        if (dto.seatGrades() != null) {
            dto.seatGrades().stream()
                    .filter(g -> g.gradeName() != null && !g.gradeName().isBlank() && g.price() != null)
                    .forEach(g -> seatGradeRepository.save(new SeatGrade(concert.getId(), g.gradeName().trim(), g.price())));
        }
        return concert;
    }

    @Transactional
    public Concert updateConcert(Long concertId, ConcertRegisterDto dto) {
        validate(dto);
        Concert concert = concertRepository.findById(concertId).orElseThrow(() -> ApiException.notFound("공연이 없습니다"));
        concert.update(dto.title().trim(), dto.eventOpen(), dto.eventStart(), dto.eventEnd(), dto.venue().trim());
        return concert;
    }

    /** 좌석을 이미 등록했거나 판매한 공연은 FairChain 기록이 남아 있으므로 지우지 않는다 */
    @Transactional
    public void deleteConcert(Long concertId) {
        if (seatRepository.countByConcertId(concertId) > 0 || ticketRepository.existsByConcertId(concertId)) {
            throw ApiException.conflict("좌석이 등록된 공연은 삭제할 수 없습니다");
        }
        seatGradeRepository.deleteByConcertId(concertId);
        concertRepository.deleteById(concertId);
    }

    private static void validate(ConcertRegisterDto dto) {
        if (dto == null || dto.title() == null || dto.title().isBlank() || dto.venue() == null || dto.venue().isBlank()
                || dto.eventOpen() == null || dto.eventStart() == null || dto.eventEnd() == null) {
            throw ApiException.badRequest("제목 · 공연장 · 예매 오픈 · 공연 시작 · 종료 시각은 필수입니다");
        }
        if (!dto.eventStart().isBefore(dto.eventEnd())) {
            throw ApiException.badRequest("공연 종료는 시작보다 뒤여야 합니다");
        }
    }

    /** "A1-A10", "B3" 같은 입력을 좌석 번호 목록으로 */
    static List<String> expand(List<String> inputs) {
        Set<String> result = new LinkedHashSet<>();
        for (String input : inputs) {
            for (String part : input.split(",")) {
                String token = part.trim();
                if (token.isEmpty()) {
                    continue;
                }
                Matcher m = RANGE.matcher(token);
                if (m.matches()) {
                    int from = Integer.parseInt(m.group(2));
                    int to = Integer.parseInt(m.group(3));
                    for (int i = Math.min(from, to); i <= Math.max(from, to) && result.size() <= MAX_SEATS_PER_REQUEST; i++) {
                        result.add(m.group(1) + i);
                    }
                } else {
                    result.add(token);
                }
            }
        }
        return new ArrayList<>(result);
    }
}
