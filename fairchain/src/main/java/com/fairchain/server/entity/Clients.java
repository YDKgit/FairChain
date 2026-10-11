package com.fairchain.server.entity;
import jakarta.persistence.*;
@Entity @Table(name="Clients") public class Clients {
 @Id @Column(name="client_server_id")
 public String clientServerId;
 @Column(name="apiKey")
 public String apiKey;
}
