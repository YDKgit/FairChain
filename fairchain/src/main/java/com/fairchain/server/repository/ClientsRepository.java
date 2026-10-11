package com.fairchain.server.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fairchain.server.entity.Clients;
public interface ClientsRepository extends JpaRepository<Clients,String> { }
