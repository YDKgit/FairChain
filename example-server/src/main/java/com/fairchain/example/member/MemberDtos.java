package com.fairchain.example.member;

import java.time.LocalDateTime;

/** 회원 관리 DTO (CD-01) */
public final class MemberDtos {

    private MemberDtos() {}

    public record SignUpRequestDto(String loginId, String password, String name, String contact) {}

    public record LoginRequestDto(String loginId, String password) {}

    public record MemberInfoResponseDto(Long id, String loginId, String name, String contact, LocalDateTime joinedAt) {

        static MemberInfoResponseDto of(Member member) {
            return new MemberInfoResponseDto(member.getId(), member.getLoginId(), member.getName(),
                    member.getContact(), member.getJoinedAt());
        }
    }
}
