package com.fairchain.example.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 회원. id가 FairChain 호출 때 userId로 쓰인다 */
@Entity
@Table(name = "member")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String loginId;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 30)
    private String contact;

    @Column(nullable = false)
    private LocalDateTime joinedAt;

    protected Member() {}

    public Member(String loginId, String passwordHash, String name, String contact) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.contact = contact;
        this.joinedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }

    public String getLoginId() { return loginId; }

    public String getPasswordHash() { return passwordHash; }

    public String getName() { return name; }

    public String getContact() { return contact; }

    public LocalDateTime getJoinedAt() { return joinedAt; }
}
