package com.fairchain.server.admin.repository;

import com.fairchain.server.admin.entity.OperatorAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OperatorAccountRepository extends JpaRepository<OperatorAccount, Long> {

    Optional<OperatorAccount> findByLoginId(String loginId);

    Optional<OperatorAccount> findByDashboardToken(String dashboardToken);

    Optional<OperatorAccount> findFirstByClientIdOrderByIdAsc(String clientId);
}
