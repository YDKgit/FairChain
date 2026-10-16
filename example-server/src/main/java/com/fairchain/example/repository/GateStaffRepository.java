package com.fairchain.example.repository;

import com.fairchain.example.entity.GateStaff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** C-0407 게이트 스태프 계정 저장소 */
public interface GateStaffRepository extends JpaRepository<GateStaff, Long> {

    Optional<GateStaff> findByLoginId(String loginId);
}
