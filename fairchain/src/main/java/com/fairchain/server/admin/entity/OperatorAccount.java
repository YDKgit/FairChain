package com.fairchain.server.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** 고객사 운영자 — 관제 대시보드 로그인용 */
@Entity
@Table(name = "operator_account")
public class OperatorAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_server_id", nullable = false)
    private String clientId;

    @Column(name = "login_id", nullable = false, unique = true, length = 100)
    private String loginId;

    /** BCrypt 해시 */
    @Column(nullable = false)
    private String password;

    @Column(name = "dashboard_token", unique = true)
    private String dashboardToken;

    protected OperatorAccount() {}

    public OperatorAccount(String clientId, String loginId, String passwordHash) {
        this.clientId = clientId;
        this.loginId = loginId;
        this.password = passwordHash;
    }

    /** 대시보드 토큰이 없으면 새로 발급 */
    public String issueDashboardToken() {
        if (dashboardToken == null) {
            dashboardToken = UUID.randomUUID().toString();
        }
        return dashboardToken;
    }

    public Long getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getLoginId() {
        return loginId;
    }

    public String getPassword() {
        return password;
    }

    public String getDashboardToken() {
        return dashboardToken;
    }
}
