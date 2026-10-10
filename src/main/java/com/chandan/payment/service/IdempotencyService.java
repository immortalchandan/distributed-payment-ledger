package com.chandan.payment.service;

import com.chandan.payment.api.dto.PaymentResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean isStandaloneDemo;

    // In-memory fallback map for standalone demo mode
    private final ConcurrentHashMap<String, String> localCache = new ConcurrentHashMap<>();

    private static final String IDEMPOTENCY_PREFIX = "idempotency:key:";
    private static final String STATUS_IN_PROGRESS = "__IN_PROGRESS__";
    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration IN_PROGRESS_LOCK_TTL = Duration.ofSeconds(30);

    @Autowired
    public IdempotencyService(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${payment.standalone-demo.enabled:false}") boolean isStandaloneDemo) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.isStandaloneDemo = isStandaloneDemo;
    }

    public boolean acquireLease(String idempotencyKey) {
        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        if (isStandaloneDemo || redisTemplate == null) {
            return localCache.putIfAbsent(redisKey, STATUS_IN_PROGRESS) == null;
        }
        try {
            Boolean success = redisTemplate.opsForValue().setIfAbsent(redisKey, STATUS_IN_PROGRESS, IN_PROGRESS_LOCK_TTL);
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            log.warn("Redis unreachable, falling back to local lease: {}", e.getMessage());
            return localCache.putIfAbsent(redisKey, STATUS_IN_PROGRESS) == null;
        }
    }

    public Optional<PaymentResponse> getCachedResponse(String idempotencyKey) {
        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        String val = null;
        if (isStandaloneDemo || redisTemplate == null) {
            val = localCache.get(redisKey);
        } else {
            try {
                val = redisTemplate.opsForValue().get(redisKey);
            } catch (Exception e) {
                val = localCache.get(redisKey);
            }
        }

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

    public void recordSuccess(String idempotencyKey, PaymentResponse response) {
        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        try {
            String json = objectMapper.writeValueAsString(response);
            localCache.put(redisKey, json);
            if (!isStandaloneDemo && redisTemplate != null) {
                redisTemplate.opsForValue().set(redisKey, json, TTL);
            }
        } catch (Exception e) {
            log.error("Failed to serialize response for idempotency key: {}", idempotencyKey, e);
        }
    }

    public void releaseLease(String idempotencyKey) {
        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        localCache.remove(redisKey);
        if (!isStandaloneDemo && redisTemplate != null) {
            try {
                redisTemplate.delete(redisKey);
            } catch (Exception ignored) {}
        }
    }
}
