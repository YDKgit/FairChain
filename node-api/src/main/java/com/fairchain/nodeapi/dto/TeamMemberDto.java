package com.fairchain.nodeapi.dto;

/**
 * 팀 리더가 보는 팀원 하나의 상태.
 *
 * @param state      마지막 응답: OK(저장함) · BEHIND(뒤처짐, 따라오는 중) · DIVERGED · INVALID_BLOCK · CHAIN_INVALID(거부)
 * @param lastSeenAt 팀원에게서 마지막으로 소식을 들은 시각 (epoch 밀리초)
 */
public record TeamMemberDto(String id, String url, Long lastBlockIndex, String lastBlockHash, ReasonCode state,
                            Long lastSeenAt) {}
