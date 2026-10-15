package com.fairchain.server.inspect;

import java.time.LocalDateTime;

/** FairChain이 받은 /api/** 요청 한 건 (점검 화면용) */
public record RequestLogEntry(
        long id,
        LocalDateTime time,
        String method,
        String path,
        String clientId,
        int status,
        long durationMs,
        String requestBody,
        String responseBody
) {}
