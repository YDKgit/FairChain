package com.fairchain.example.service;

import com.fairchain.example.common.BadRequestException;
import com.fairchain.example.common.ConflictException;
import com.fairchain.example.common.NotFoundException;
import com.fairchain.example.common.UnauthorizedException;
import com.fairchain.example.dto.LoginRequestDto;
import com.fairchain.example.dto.MemberUpdateRequestDto;
import com.fairchain.example.dto.PasswordChangeRequestDto;
import com.fairchain.example.dto.SignUpRequestDto;
import com.fairchain.example.dto.WithdrawRequestDto;
import com.fairchain.example.entity.Member;
import com.fairchain.example.repository.MemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/** C-0108 회원 서비스 — 세션 생성 · 종료는 Controller */
@Service
public class MemberService {

    /** 비밀번호 규칙 — 명세에 문구가 없어 8자 이상, 영문 + 숫자 포함으로 정함 */
    public static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,}$");
    public static final Pattern CONTACT_PATTERN = Pattern.compile("^01[0-9]-?\\d{3,4}-?\\d{4}$");

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final QueueService queueService;
    private final Clock clock;

    public MemberService(MemberRepository memberRepository, PasswordEncoder passwordEncoder,
                         QueueService queueService, Clock clock) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.queueService = queueService;
        this.clock = clock;
    }

    @Transactional
    public Member signUp(SignUpRequestDto dto) {
        validatePassword(dto.password());
        validateContact(dto.contact());
        if (memberRepository.findByLoginId(dto.loginId()).isPresent()) {
            throw new ConflictException("이미 사용 중인 아이디입니다");
        }
        Member member = new Member(dto.loginId(), passwordEncoder.encode(dto.password()),
                dto.name(), dto.contact(), LocalDateTime.now(clock));
        return memberRepository.save(member);
    }

    /** 아이디 없음 · 비밀번호 불일치 모두 401 */
    @Transactional(readOnly = true)
    public Member login(LoginRequestDto dto) {
        Member member = memberRepository.findByLoginId(dto.loginId())
                .orElseThrow(() -> new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다"));
        if (!passwordEncoder.matches(dto.password(), member.getPasswordHash())) {
            throw new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다");
        }
        return member;
    }

    /** 모든 대기열에서 이탈 — 실패해도 로그아웃은 완료 */
    public void logout(Long memberId) {
        try {
            queueService.leaveAllQueues(memberId);
        } catch (RuntimeException e) {
            log.warn("로그아웃 중 대기열 이탈 실패 memberId={}: {}", memberId, e.getMessage());
        }
    }

    @Transactional
    public void changePassword(Long memberId, PasswordChangeRequestDto dto) {
        Member member = getMemberInfo(memberId);
        if (!passwordEncoder.matches(dto.currentPassword(), member.getPasswordHash())) {
            throw new BadRequestException("현재 비밀번호가 올바르지 않습니다");
        }
        validatePassword(dto.newPassword());
        if (passwordEncoder.matches(dto.newPassword(), member.getPasswordHash())) {
            throw new BadRequestException("새 비밀번호가 기존 비밀번호와 같습니다");
        }
        member.changePassword(passwordEncoder.encode(dto.newPassword()));
        memberRepository.save(member);
    }

    @Transactional(readOnly = true)
    public Member getMemberInfo(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new NotFoundException("회원을 찾을 수 없습니다"));
    }

    @Transactional
    public Member updateMemberInfo(Long memberId, MemberUpdateRequestDto dto) {
        validateContact(dto.contact());
        Member member = getMemberInfo(memberId);
        member.updateInfo(dto.name(), dto.contact());
        return memberRepository.save(member);
    }

    /** 진행 중 예매 · 대기열은 확인하지 않는다(SD-107 팀 결정). 티켓 · 주문은 남긴다 */
    @Transactional
    public void withdraw(Long memberId, WithdrawRequestDto dto) {
        Member member = getMemberInfo(memberId);
        if (!passwordEncoder.matches(dto.password(), member.getPasswordHash())) {
            throw new BadRequestException("비밀번호가 올바르지 않습니다");
        }
        memberRepository.deleteById(memberId);
    }

    private void validatePassword(String password) {
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw new BadRequestException("비밀번호는 8자 이상, 영문과 숫자를 포함해야 합니다");
        }
    }

    private void validateContact(String contact) {
        if (!CONTACT_PATTERN.matcher(contact).matches()) {
            throw new BadRequestException("연락처 형식이 올바르지 않습니다");
        }
    }
}
