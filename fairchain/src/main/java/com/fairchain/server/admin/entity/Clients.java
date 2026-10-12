package com.fairchain.server.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 고객사 서버 (예제서버 등) */
@Entity
@Table(name = "clients")
public class Clients {

    @Id
    @Column(name = "client_server_id")
    private String clientId;

    @Column(name = "api_key", nullable = false, unique = true)
    private String apiKey;

    protected Clients() {}

    public Clients(String clientId, String apiKey) {
        this.clientId = clientId;
        this.apiKey = apiKey;
    }

    public String getClientId() {
        return clientId;
    }

    public String getApiKey() {
        return apiKey;
    }
}
