package com.fairchain.example.service;

import com.fairchain.example.common.BadRequestException;
import com.fairchain.example.common.ConflictException;
import com.fairchain.example.common.FairChainException;
import com.fairchain.example.common.UnauthorizedException;
import com.fairchain.example.dto.LoginRequestDto;
import com.fairchain.example.dto.MemberUpdateRequestDto;
import com.fairchain.example.dto.PasswordChangeRequestDto;
import com.fairchain.example.dto.SignUpRequestDto;
import com.fairchain.example.dto.WithdrawRequestDto;
import com.fairchain.example.entity.Concert;
import com.fairchain.example.entity.Member;
import com.fairchain.example.entity.Ticket;
import com.fairchain.example.support.ServiceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class MemberServiceTest extends ServiceTestSupport {

    @Autowired
    private MemberService memberService;

    private SignUpRequestDto signUp(String loginId) {
        return new SignUpRequestDto(loginId, PASSWORD, "홍길동", "010-1234-5678");
    }

    @Test
    void 회원가입하면_비밀번호는_해시로_저장() {
        Member member = memberService.signUp(signUp("user1"));
        assertNotEquals(PASSWORD, member.getPasswordHash());
        assertTrue(passwordEncoder.matches(PASSWORD, member.getPasswordHash()));
    }

    @Test
    void 아이디가_중복이면_409() {
        memberService.signUp(signUp("user1"));
        assertThrows(ConflictException.class, () -> memberService.signUp(signUp("user1")));
    }

    @Test
    void 가입_비밀번호가_규칙에_맞지_않으면_400() {
        assertThrows(BadRequestException.class,
                () -> memberService.signUp(new SignUpRequestDto("user1", "short1", "홍길동", "010-1234-5678")));
        assertThrows(BadRequestException.class,
                () -> memberService.signUp(new SignUpRequestDto("user1", "onlyletters", "홍길동", "010-1234-5678")));
    }

    @Test
    void 로그인_아이디가_없거나_비밀번호가_틀리면_401() {
        member("user1");
        assertThrows(UnauthorizedException.class, () -> memberService.login(new LoginRequestDto("nobody", PASSWORD)));
        assertThrows(UnauthorizedException.class, () -> memberService.login(new LoginRequestDto("user1", "wrong1234")));
        assertEquals("user1", memberService.login(new LoginRequestDto("user1", PASSWORD)).getLoginId());
    }

    @Test
    void 비밀번호_변경_현재_비밀번호가_틀리면_400() {
        Member member = member("user1");
        assertThrows(BadRequestException.class, () -> memberService.changePassword(member.getId(),
                new PasswordChangeRequestDto("wrong1234", "newpass123")));
    }

    @Test
    void 비밀번호_변경_새_비밀번호가_규칙_위반이면_400() {
        Member member = member("user1");
        assertThrows(BadRequestException.class, () -> memberService.changePassword(member.getId(),
                new PasswordChangeRequestDto(PASSWORD, "short")));
    }

    @Test
    void 비밀번호_변경_기존과_같으면_400() {
        Member member = member("user1");
        assertThrows(BadRequestException.class, () -> memberService.changePassword(member.getId(),
                new PasswordChangeRequestDto(PASSWORD, PASSWORD)));
    }

    @Test
    void 비밀번호_변경_성공하면_새_비밀번호로_로그인() {
        Member member = member("user1");
        memberService.changePassword(member.getId(), new PasswordChangeRequestDto(PASSWORD, "newpass123"));
        assertEquals(member.getId(), memberService.login(new LoginRequestDto("user1", "newpass123")).getId());
    }

    @Test
    void 연락처_형식이_틀리면_400() {
        Member member = member("user1");
        assertThrows(BadRequestException.class,
                () -> memberService.updateMemberInfo(member.getId(), new MemberUpdateRequestDto("김철수", "12345")));
        assertEquals("김철수", memberService.updateMemberInfo(member.getId(),
                new MemberUpdateRequestDto("김철수", "01098765432")).getName());
    }

    @Test
    void 탈퇴_비밀번호가_틀리면_400() {
        Member member = member("user1");
        assertThrows(BadRequestException.class,
                () -> memberService.withdraw(member.getId(), new WithdrawRequestDto("wrong1234")));
    }

    @Test
    void 탈퇴하면_회원만_지우고_티켓은_남김() {
        Member member = member("user1");
        Concert concert = openConcert();
        Ticket ticket = activeTicket(member, concert, seat(grade(concert), "A-1"));

        memberService.withdraw(member.getId(), new WithdrawRequestDto(PASSWORD));

        assertFalse(memberRepository.existsById(member.getId()));
        assertTrue(ticketRepository.existsById(ticket.getTicketId()));
    }

    @Test
    void 로그아웃은_대기열_이탈이_실패해도_완료() {
        Member member = member("user1");
        doThrow(new FairChainException(0, "timeout", null)).when(queueAccess).leaveAll(member.getId());
        assertDoesNotThrow(() -> memberService.logout(member.getId()));
        verify(queueAccess).leaveAll(member.getId());
    }
}
