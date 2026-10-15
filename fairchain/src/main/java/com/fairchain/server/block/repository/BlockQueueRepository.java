package com.fairchain.server.block.repository;

import com.fairchain.nodeapi.dto.BlockRecordDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 블록 기록 대기 줄 (Redis LIST "block:queue") — 노드로 보낼 해시만.
 * 같은 좌석은 버전 순서대로 기록돼야 하므로 앞에서 꺼내고, 실패하면 다시 앞에 넣는다.
 */
@Repository
public class BlockQueueRepository {

    private static final String KEY = "block:queue";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public BlockQueueRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void enqueue(BlockRecordDto dto) {
        redis.opsForList().rightPush(KEY, write(dto));
    }

    public BlockRecordDto dequeue() {
        String json = redis.opsForList().leftPop(KEY);
        return json == null ? null : read(json);
    }

    /** 전송 실패 시 순서를 지키기 위해 맨 앞으로 되돌림 */
    public void pushFront(BlockRecordDto dto) {
        redis.opsForList().leftPush(KEY, write(dto));
    }

    public long size() {
        Long size = redis.opsForList().size(KEY);
        return size == null ? 0L : size;
    }

    private String write(BlockRecordDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private BlockRecordDto read(String json) {
        try {
            return objectMapper.readValue(json, BlockRecordDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
