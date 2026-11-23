package com.fairchain.example.reservation;

import com.fairchain.api.dto.ReallocationListDto;
import com.fairchain.example.common.ApiException;
import com.fairchain.example.concert.Concert;
import com.fairchain.example.concert.ConcertRepository;
import com.fairchain.example.concert.SeatGrade;
import com.fairchain.example.concert.SeatGradeRepository;
import com.fairchain.example.member.Member;
import com.fairchain.example.member.MemberRepository;
import com.fairchain.example.reservation.ReallocationDtos.AdminStatusDto;
import com.fairchain.example.reservation.ReallocationDtos.HeldSeatDto;
import com.fairchain.example.reservation.ReallocationDtos.OfferDto;
import com.fairchain.example.reservation.ReallocationDtos.RoundDto;
import com.fairchain.example.reservation.ReallocationDtos.RoundOfferDto;
import com.fairchain.example.reservation.ReallocationDtos.StatusDto;
import com.fairchain.example.reservation.ReservationDtos.SeatDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 취소표 재배정 조회 — 회원 화면(내 제안 · 현황)과 운영자 화면(보류 취소표 · 회차 · 명단). 상태를 바꾸지 않는다 */
@Service
public class ReallocationQueryService {

    private final ReallocationOfferRepository offerRepository;
    private final ReallocationRoundRepository roundRepository;
    private final SeatRepository seatRepository;
    private final SeatGradeRepository seatGradeRepository;
    private final ConcertRepository concertRepository;
    private final MemberRepository memberRepository;
    private final ReservationAccess reservationAccess;
    private final ReallocationSchedule schedule;

    public ReallocationQueryService(ReallocationOfferRepository offerRepository, ReallocationRoundRepository roundRepository,
                                    SeatRepository seatRepository, SeatGradeRepository seatGradeRepository,
                                    ConcertRepository concertRepository, MemberRepository memberRepository,
                                    ReservationAccess reservationAccess, ReallocationSchedule schedule) {
        this.offerRepository = offerRepository;
        this.roundRepository = roundRepository;
        this.seatRepository = seatRepository;
        this.seatGradeRepository = seatGradeRepository;
        this.concertRepository = concertRepository;
        this.memberRepository = memberRepository;
        this.reservationAccess = reservationAccess;
        this.schedule = schedule;
    }

    /** 나에게 와 있는 제안 (기한 안의 것만) */
    @Transactional(readOnly = true)
    public List<OfferDto> openOffers(Long memberId) {
        LocalDateTime now = LocalDateTime.now();
        return offerRepository.findByMemberIdAndStatusOrderByExpiresAtAsc(memberId, OfferStatus.OFFERED).stream()
                .filter(o -> now.isBefore(o.getExpiresAt()))
                .map(this::offerDto)
                .toList();
    }

    /** 공연의 재배정 현황. memberId가 있으면 명단 순번과 내 제안도 함께 */
    @Transactional(readOnly = true)
    public StatusDto status(Long concertId, Long memberId) {
        Concert concert = concert(concertId);
        LocalDateTime now = LocalDateTime.now();
        long held = seatRepository.countByConcertIdAndStatus(concertId, SeatStatus.CANCELLED);
        boolean finalWindow = schedule.isFinalWindow(concert, now);
        Optional<ReallocationRound> open = roundRepository.findOpen(concertId);
        ReallocationRound.Status roundStatus = open.map(ReallocationRound::getStatus).orElse(null);
        Integer candidateCount = open.map(ReallocationRound::getCandidateCount).orElse(null);
        Long currentSequence = open.flatMap(r -> offerRepository.findCurrent(r.getId()))
                .map(ReallocationOffer::getSequenceNumber).orElse(null);
        LocalDateTime releaseAt = open.map(ReallocationRound::getReleaseAt).orElse(null);
        LocalDateTime nextOfferAt = (held > 0 && open.isEmpty() && !finalWindow) ? schedule.nextOfferAt(concertId, now) : null;

        Long myRank = null;
        Long myQueueNumber = null;
        OfferStatus myOfferStatus = null;
        OfferDto myOffer = null;
        if (memberId != null) {
            Long[] place = placeInCandidates(concertId, memberId);
            if (open.isPresent()) {
                Optional<ReallocationOffer> mine = offerRepository.findFirstByRoundIdAndMemberId(open.get().getId(), memberId);
                myRank = mine.map(ReallocationOffer::getSequenceNumber).orElse(null);
                myOfferStatus = mine.map(ReallocationOffer::getStatus).orElse(null);
                myOffer = mine.filter(o -> o.isUsableBy(memberId, now)).map(this::offerDto).orElse(null);
            } else if (!finalWindow) {
                // 취소표가 아직 없어도 명단 순번은 보여 준다 — 안 그러면 명단에 있는 사람에게도 "명단에 없음"으로 보인다
                myRank = place == null ? null : place[0];
            }
            myQueueNumber = place == null ? null : place[1];
        }
        return new StatusDto(concertId, held, roundStatus, candidateCount, currentSequence, nextOfferAt, releaseAt,
                finalWindow, myRank, myQueueNumber, myOfferStatus, myOffer);
    }

    @Transactional(readOnly = true)
    public AdminStatusDto adminStatus(Long concertId) {
        Concert concert = concert(concertId);
        LocalDateTime now = LocalDateTime.now();
        Map<Long, SeatGrade> grades = seatGradeRepository.mapByConcertId(concertId);
        List<HeldSeatDto> heldSeats = seatRepository.findByConcertIdAndStatusOrderByIdAsc(concertId, SeatStatus.CANCELLED).stream()
                .map(s -> new HeldSeatDto(s.getId(), s.getSeatNumber(), SeatGrade.nameOf(grades.get(s.getSeatGradeId())),
                        s.getCancelledAt(), s.getReallocationRoundId() != null))
                .toList();
        Optional<ReallocationRound> open = roundRepository.findOpen(concertId);
        List<RoundDto> history = roundRepository.findByConcertIdOrderByIdDesc(concertId).stream()
                .filter(r -> !r.isOpen())
                .limit(5)
                .map(this::roundDto)
                .toList();
        boolean finalWindow = schedule.isFinalWindow(concert, now);
        LocalDateTime nextOfferAt = (!heldSeats.isEmpty() && open.isEmpty() && !finalWindow) ? schedule.nextOfferAt(concertId, now) : null;
        return new AdminStatusDto(concertId, concert.getTitle(), finalWindow, nextOfferAt, schedule.toDto(),
                heldSeats, open.map(this::roundDto).orElse(null), history);
    }

    /** 회차 하나를 운영자 화면용으로 (명단 · 제안 상태 · 남은 취소표) */
    @Transactional(readOnly = true)
    public RoundDto roundDto(ReallocationRound round) {
        Map<Long, String> seatNumbers = seatRepository.findByConcertIdOrderByIdAsc(round.getConcertId()).stream()
                .collect(Collectors.toMap(Seat::getId, Seat::getSeatNumber));
        List<RoundOfferDto> offers = new ArrayList<>();
        for (ReallocationOffer o : offerRepository.findByRoundIdOrderBySequenceNumberAsc(round.getId())) {
            String name = memberRepository.findById(o.getMemberId()).map(Member::getName).orElse("회원 " + o.getMemberId());
            offers.add(new RoundOfferDto(o.getSequenceNumber(), o.getMemberId(), name, o.getStatus(), o.getOfferedAt(),
                    o.getExpiresAt(), o.getSeatId() == null ? null : seatNumbers.get(o.getSeatId())));
        }
        long remaining = seatRepository.countByReallocationRoundIdAndStatus(round.getId(), SeatStatus.CANCELLED);
        return new RoundDto(round.getId(), round.getStatus(), round.getStartedAt(), round.getReleaseAt(), round.getFinishedAt(),
                round.getCandidateCount(), round.getSeatCount(), remaining, offers);
    }

    private Concert concert(Long concertId) {
        return concertRepository.findById(concertId).orElseThrow(() -> ApiException.notFound("공연이 없습니다"));
    }

    /**
     * FairChain 명단에서 내 자리: [명단 순번(취소표 차례), 대기열 진입 순번]. 명단에 없거나 FairChain이 답하지 않으면 null.
     * 진입 순번을 받지 못했으면 두 번째 값만 null
     */
    private Long[] placeInCandidates(Long concertId, Long memberId) {
        try {
            ReallocationListDto list = reservationAccess.getReallocationCandidates(concertId);
            int index = list.userIds().indexOf(memberId);
            if (index < 0) {
                return null;
            }
            List<Long> numbers = list.sequenceNumbers();
            return new Long[]{(long) index + 1, numbers != null && index < numbers.size() ? numbers.get(index) : null};
        } catch (RuntimeException e) {
            return null;
        }
    }

    private OfferDto offerDto(ReallocationOffer o) {
        String title = concertRepository.findById(o.getConcertId()).map(Concert::getTitle).orElse("-");
        Map<Long, SeatGrade> grades = seatGradeRepository.mapByConcertId(o.getConcertId());
        List<SeatDto> seats = seatRepository.findByReallocationRoundIdAndStatusOrderByIdAsc(o.getRoundId(), SeatStatus.CANCELLED).stream()
                .map(s -> SeatDto.of(s, grades.get(s.getSeatGradeId())))
                .toList();
        return new OfferDto(o.getId(), o.getConcertId(), title, o.getOfferToken(), o.getOfferedAt(), o.getExpiresAt(), seats);
    }
}
