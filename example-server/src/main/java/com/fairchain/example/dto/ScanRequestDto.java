package com.fairchain.example.dto;

import jakarta.validation.constraints.NotBlank;

/** 보완 — QR 스캔 요청 (POST /staff/scan) */
public record ScanRequestDto(@NotBlank String qrToken) {}
