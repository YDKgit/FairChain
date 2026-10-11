package com.fairchain.server.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fairchain.server.entity.Event;
public interface EventRepository extends JpaRepository<Event,Long> { }
