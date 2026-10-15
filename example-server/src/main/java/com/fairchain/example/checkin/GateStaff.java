package com.fairchain.example.checkin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "gate_staff")
public class GateStaff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long staffId;

    @Column(nullable = false, unique = true, length = 50)
    private String loginId;

    /** 해시된 비밀번호 */
    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    protected GateStaff() {}

    public GateStaff(String loginId, String password, String name) {
        this.loginId = loginId;
        this.password = password;
        this.name = name;
    }

    public Long getStaffId() { return staffId; }

    public String getLoginId() { return loginId; }

    public String getPassword() { return password; }

    public String getName() { return name; }
}
