package com.fairchain.example.controller;

import com.fairchain.example.common.LoginMember;
import com.fairchain.example.common.SessionConst;
import com.fairchain.example.dto.LoginRequestDto;
import com.fairchain.example.dto.MemberInfoResponseDto;
import com.fairchain.example.dto.MemberUpdateRequestDto;
import com.fairchain.example.dto.PasswordChangeRequestDto;
import com.fairchain.example.dto.SignUpRequestDto;
import com.fairchain.example.dto.WithdrawRequestDto;
import com.fairchain.example.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** C-0107 회원 관리 (SS-01) */
@RestController
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @PostMapping("/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberInfoResponseDto signUp(@Valid @RequestBody SignUpRequestDto dto) {
        return MemberInfoResponseDto.from(memberService.signUp(dto));
    }

    @PostMapping("/login")
    public MemberInfoResponseDto login(@Valid @RequestBody LoginRequestDto dto, HttpServletRequest request) {
        var member = memberService.login(dto);
        SessionSupport.login(request, SessionConst.LOGIN_MEMBER_ID, member.getId());
        return MemberInfoResponseDto.from(member);
    }

    @PostMapping("/logout")
    public void logout(@LoginMember Long memberId, HttpServletRequest request) {
        memberService.logout(memberId);
        SessionSupport.invalidate(request);
    }

    @GetMapping("/members/me")
    public MemberInfoResponseDto getInfo(@LoginMember Long memberId) {
        return MemberInfoResponseDto.from(memberService.getMemberInfo(memberId));
    }

    @PatchMapping("/members/me")
    public MemberInfoResponseDto updateInfo(@LoginMember Long memberId, @Valid @RequestBody MemberUpdateRequestDto dto) {
        return MemberInfoResponseDto.from(memberService.updateMemberInfo(memberId, dto));
    }

    @PatchMapping("/members/me/password")
    public void changePassword(@LoginMember Long memberId, @Valid @RequestBody PasswordChangeRequestDto dto) {
        memberService.changePassword(memberId, dto);
    }

    @DeleteMapping("/members/me")
    public void withdraw(@LoginMember Long memberId, @Valid @RequestBody WithdrawRequestDto dto,
                         HttpServletRequest request) {
        memberService.withdraw(memberId, dto);
        SessionSupport.invalidate(request);
    }
}
