package com.fairchain.example.checkin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GateStaffRepository extends JpaRepository<GateStaff, Long> {

    Optional<GateStaff> findByLoginId(String loginId);
}
