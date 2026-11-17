package com.fairchain.example.member;

import com.fairchain.example.common.DataInitializer;
import com.fairchain.example.common.ExampleProperties;
import com.fairchain.example.common.SessionUser;
import com.fairchain.example.member.MemberDtos.LoginRequestDto;
import com.fairchain.example.member.MemberDtos.MemberInfoResponseDto;
import com.fairchain.example.member.MemberDtos.SignUpRequestDto;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/members")
public class MemberController {

    private final MemberService memberService;
    private final ExampleProperties properties;

    public MemberController(MemberService memberService, ExampleProperties properties) {
        this.memberService = memberService;
        this.properties = properties;
    }

    @PostMapping
    public MemberInfoResponseDto signUp(@RequestBody SignUpRequestDto dto) {
        return MemberInfoResponseDto.of(memberService.signUp(dto));
    }

    @PostMapping("/login")
    public MemberInfoResponseDto login(@RequestBody LoginRequestDto dto, HttpSession session) {
        Member member = memberService.login(dto);
        SessionUser.loginMember(session, member.getId());
        return MemberInfoResponseDto.of(member);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        Long memberId = SessionUser.memberIdOrNull(session);
        if (memberId != null) {
            memberService.logout(memberId);
            SessionUser.logoutMember(session);
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public MemberInfoResponseDto getInfo(HttpSession session) {
        return MemberInfoResponseDto.of(memberService.getMemberInfo(SessionUser.memberId(session)));
    }

    /** 시연 계정 목록 — 시연 사이트라 비밀번호도 함께 준다 (seed.member-password가 비어 있으면 빈 목록) */
    @GetMapping("/demo")
    public List<Map<String, String>> demoAccounts() {
        String password = properties.seed() == null ? null : properties.seed().memberPassword();
        if (password == null || password.isBlank()) {
            return List.of();
        }
        return DataInitializer.DEMO_MEMBERS.stream()
                .map(m -> Map.of("loginId", m[0], "name", m[1], "password", password))
                .toList();
    }
}
