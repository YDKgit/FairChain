package com.fairchain.server.admin.service;

import com.fairchain.server.admin.entity.Clients;
import com.fairchain.server.admin.entity.OperatorAccount;
import com.fairchain.server.admin.repository.ClientsRepository;
import com.fairchain.server.admin.repository.OperatorAccountRepository;
import com.fairchain.server.common.config.FairChainProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발용: fairchain.seed.* 설정이 있으면 고객사 · 운영자 계정을 만들어 둔다 (이미 있으면 건너뜀).
 * 고객사 가입 기능은 설계 범위 밖이라 이 방식으로 등록한다.
 */
@Component
public class SeedDataInitializer implements ApplicationRunner {

    private final FairChainProperties properties;
    private final ClientsRepository clientsRepository;
    private final OperatorAccountRepository operatorAccountRepository;
    private final AuthService authService;

    public SeedDataInitializer(FairChainProperties properties, ClientsRepository clientsRepository,
                               OperatorAccountRepository operatorAccountRepository, AuthService authService) {
        this.properties = properties;
        this.clientsRepository = clientsRepository;
        this.operatorAccountRepository = operatorAccountRepository;
        this.authService = authService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        FairChainProperties.Seed seed = properties.seed();
        if (seed == null || seed.clientId() == null || seed.apiKey() == null) {
            return;
        }
        if (!clientsRepository.existsById(seed.clientId())) {
            clientsRepository.save(new Clients(seed.clientId(), seed.apiKey()));
        }
        if (seed.operatorLoginId() != null && seed.operatorPassword() != null
                && operatorAccountRepository.findByLoginId(seed.operatorLoginId()).isEmpty()) {
            operatorAccountRepository.save(new OperatorAccount(
                    seed.clientId(), seed.operatorLoginId(), authService.encodePassword(seed.operatorPassword())));
        }
    }
}
