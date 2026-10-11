package com.fairchain.server.entity;
import jakarta.persistence.*;
@Entity @Table(name="OperatorAccount") public class OperatorAccount {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="id")
 public Long id;
 @Column(name="client_server_id")
 public String clientServerId;
 @Column(name="login_id")
 public String loginId;
 @Column(name="password")
 public String password;
 @Column(name="dashboardToken")
 public String dashboardToken;
}
