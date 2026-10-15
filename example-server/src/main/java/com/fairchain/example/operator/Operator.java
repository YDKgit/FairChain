package com.fairchain.example.operator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 예제서버 운영자 (FairChain 운영자 계정과는 별개) */
@Entity
@Table(name = "operator")
public class Operator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long operatorId;

    @Column(nullable = false, unique = true, length = 50)
    private String loginId;

    /** 해시된 비밀번호 */
    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    protected Operator() {}

    public Operator(String loginId, String password, String name) {
        this.loginId = loginId;
        this.password = password;
        this.name = name;
    }

    public Long getOperatorId() { return operatorId; }

    public String getLoginId() { return loginId; }

    public String getPassword() { return password; }

    public String getName() { return name; }
}
