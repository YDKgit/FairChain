package com.fairchain.example.member;

import com.fairchain.example.common.ApiException;
import com.fairchain.example.member.MemberDtos.LoginRequestDto;
import com.fairchain.example.member.MemberDtos.SignUpRequestDto;
import com.fairchain.example.queue.QueueService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final QueueService queueService;

    public MemberService(MemberRepository memberRepository, BCryptPasswordEncoder passwordEncoder,
                         QueueService queueService) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.queueService = queueService;
    }

    /** 아이디 중복 확인, 비밀번호 해시 후 저장 */
    @Transactional
    public Member signUp(SignUpRequestDto dto) {
        if (dto == null || blank(dto.loginId()) || blank(dto.password()) || blank(dto.name())) {
            throw ApiException.badRequest("아이디 · 비밀번호 · 이름은 필수입니다");
        }
        if (memberRepository.existsByLoginId(dto.loginId())) {
            throw ApiException.conflict("이미 사용 중인 아이디입니다");
        }
        return memberRepository.save(new Member(dto.loginId().trim(), passwordEncoder.encode(dto.password()),
                dto.name().trim(), dto.contact()));
    }

    @Transactional(readOnly = true)
    public Member login(LoginRequestDto dto) {
        return memberRepository.findByLoginId(dto == null ? null : dto.loginId())
                .filter(m -> passwordEncoder.matches(dto.password(), m.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("아이디 또는 비밀번호가 틀렸습니다"));
    }

    /** 세션 종료 전에 모든 대기열에서 이탈 (SD-103) */
    public void logout(Long memberId) {
        queueService.leaveAllQueues(memberId);
    }

    @Transactional(readOnly = true)
    public Member getMemberInfo(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> ApiException.notFound("회원이 없습니다"));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
