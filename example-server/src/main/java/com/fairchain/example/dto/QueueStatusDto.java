package com.fairchain.example.dto;

/** C-0201 대기열 상태
 *
 * @param state WAITING / ADMITTED / EXPIRED / NOT_FOUND
 */
public record QueueStatusDto(String state, Long rank, Long waitingCount) {}
