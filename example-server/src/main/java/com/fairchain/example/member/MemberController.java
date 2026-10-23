package com.fairchain.example.member;

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

@RestController
@RequestMapping("/api/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
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
}
