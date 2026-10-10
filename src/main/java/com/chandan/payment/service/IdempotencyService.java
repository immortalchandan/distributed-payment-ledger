package com.chandan.payment.service;

import com.chandan.payment.api.dto.PaymentResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String IDEMPOTENCY_PREFIX = "idempotency:key:";
    private static final String STATUS_IN_PROGRESS = "__IN_PROGRESS__";
    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration IN_PROGRESS_LOCK_TTL = Duration.ofSeconds(30);

    /**
     * Atomically attempts to acquire an idempotency lease.
     * Returns true if this is a NEW request that can proceed.
     * Returns false if another request is already in-progress or completed.
     */
    public boolean acquireLease(String idempotencyKey) {
        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        Boolean success = redisTemplate.opsForValue().setIfAbsent(redisKey, STATUS_IN_PROGRESS, IN_PROGRESS_LOCK_TTL);
        return Boolean.TRUE.equals(success);
    }

    /**
     * Retrieves the cached response for an idempotency key if it was previously completed.
     */
    public Optional<PaymentResponse> getCachedResponse(String idempotencyKey) {
        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        String val = redisTemplate.opsForValue().get(redisKey);
        if (val == null || STATUS_IN_PROGRESS.equals(val)) {
            return Optional.empty();
        }
        try {
            PaymentResponse response = objectMapper.readValue(val, PaymentResponse.class);
            return Optional.of(response);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached response for key: {}", idempotencyKey, e);
            return Optional.empty();
        }
    }

    /**
     * Caches the finalized response for 24 hours.
     */
    public void recordSuccess(String idempotencyKey, PaymentResponse response) {
        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(redisKey, json, TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response for idempotency key: {}", idempotencyKey, e);
        }
    }

    /**
     * Releases lease on failure so retry attempts can proceed immediately.
     */
    public void releaseLease(String idempotencyKey) {
        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        redisTemplate.delete(redisKey);
    }
}
