package com.fairchain.server.admin.repository;

import com.fairchain.server.admin.entity.Clients;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClientsRepository extends JpaRepository<Clients, String> {

    Optional<Clients> findByApiKey(String apiKey);
}
