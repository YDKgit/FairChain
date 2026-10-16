package com.fairchain.example.dto;

import com.fairchain.example.entity.Member;

import java.time.LocalDateTime;

/** C-0106 회원 정보 (비밀번호 해시 제외) */
public record MemberInfoResponseDto(String loginId, String name, String contact, LocalDateTime joinedAt) {

    public static MemberInfoResponseDto from(Member member) {
        return new MemberInfoResponseDto(member.getLoginId(), member.getName(), member.getContact(), member.getJoinedAt());
    }
}
