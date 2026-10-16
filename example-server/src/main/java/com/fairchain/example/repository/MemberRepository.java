package com.fairchain.example.repository;

import com.fairchain.example.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** C-0109 회원 저장소 — 설계의 delete(id)는 deleteById(id) */
public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByLoginId(String loginId);
}
