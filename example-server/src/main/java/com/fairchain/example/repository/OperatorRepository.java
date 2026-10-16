package com.fairchain.example.repository;

import com.fairchain.example.entity.Operator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** C-0607 운영자 계정 저장소 */
public interface OperatorRepository extends JpaRepository<Operator, Long> {

    Optional<Operator> findByLoginId(String loginId);
}
