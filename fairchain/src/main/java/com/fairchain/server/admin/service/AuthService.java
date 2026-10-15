package com.fairchain.server.admin.service;

import com.fairchain.server.admin.dto.LoginRequestDto;
import com.fairchain.server.admin.dto.LoginResponseDto;
import com.fairchain.server.admin.entity.Clients;
import com.fairchain.server.admin.entity.OperatorAccount;
import com.fairchain.server.admin.repository.ClientsRepository;
import com.fairchain.server.admin.repository.OperatorAccountRepository;
import com.fairchain.server.common.ErrorCode;
import com.fairchain.server.common.FairChainException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final ClientsRepository clientsRepository;
    private final OperatorAccountRepository operatorAccountRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(ClientsRepository clientsRepository, OperatorAccountRepository operatorAccountRepository) {
        this.clientsRepository = clientsRepository;
        this.operatorAccountRepository = operatorAccountRepository;
    }

    /** 고객사 서버 인증 (ApiKeyInterceptor) */
    @Transactional(readOnly = true)
    public Clients authenticateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new FairChainException(ErrorCode.UNAUTHORIZED, "API Key가 없습니다");
        }
        return clientsRepository.findByApiKey(apiKey)
                .orElseThrow(() -> new FairChainException(ErrorCode.UNAUTHORIZED, "유효하지 않은 API Key입니다"));
    }

    /** 운영자 로그인 → 대시보드 토큰 */
    @Transactional
    public LoginResponseDto login(LoginRequestDto dto) {
        OperatorAccount account = operatorAccountRepository.findByLoginId(dto.loginId())
                .filter(a -> passwordEncoder.matches(dto.password(), a.getPassword()))
                .orElseThrow(() -> new FairChainException(ErrorCode.UNAUTHORIZED, "아이디 또는 비밀번호가 틀렸습니다"));
        return new LoginResponseDto(account.getClientId(), account.issueDashboardToken());
    }

    @Transactional(readOnly = true)
    public OperatorAccount authenticateDashboardToken(String dashboardToken) {
        if (dashboardToken == null || dashboardToken.isBlank()) {
            throw new FairChainException(ErrorCode.UNAUTHORIZED, "대시보드 토큰이 없습니다");
        }
        return operatorAccountRepository.findByDashboardToken(dashboardToken)
                .orElseThrow(() -> new FairChainException(ErrorCode.UNAUTHORIZED, "유효하지 않은 대시보드 토큰입니다"));
    }

    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}
