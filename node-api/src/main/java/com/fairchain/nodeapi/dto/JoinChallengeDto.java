package com.fairchain.nodeapi.dto;

/**
 * 가입 전 확인 요청.
 *
 * @param nonce 리더가 이번 요청에만 쓰는 무작위 값 (영문 · 숫자 · '-' · '_', 16~128자). 예전 증명을 다시 쓰지 못하게 한다
 */
public record JoinChallengeDto(String nonce) {}
