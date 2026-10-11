package com.fairchain.server.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.fairchain.server.entity.Admission;
public interface AdmissionRepository extends JpaRepository<Admission,Long> { }
