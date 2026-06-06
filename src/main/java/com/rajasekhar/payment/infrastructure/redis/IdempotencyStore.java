package com.rajasekhar.payment.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed idempotency store.
 *
 * Two-layer idempotency strategy:
 *  Layer 1 — Redis check (fast path, O(1), sub-millisecond).
 *             Returns cached response immediately for duplicate keys.
 *             TTL is set to 24 hours — enough for client retry windows.
 *  Layer 2 — DB unique constraint on idempotency_key (safety net).
 *             Catches the rare race where two requests for the same key
 *             arrive within the same Redis TTL window before either is stored.
 *
 * Key format: "idempotency:{key}" — namespaced to avoid collisions with
 * other Redis data in the same instance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyStore {

    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /**
     * Check if this idempotency key has been seen before.
     * Returns the cached payment ID if found.
     */
    public Optional<String> getCachedPaymentId(String idempotencyKey) {
        String value = redisTemplate.opsForValue().get(buildKey(idempotencyKey));
        return Optional.ofNullable(value);
    }

    /**
     * Store the mapping from idempotency key to payment ID.
     * Uses SET NX (set-if-not-exists) semantics to handle the race window.
     */
    public boolean storeIfAbsent(String idempotencyKey, String paymentId) {
        Boolean stored = redisTemplate.opsForValue()
            .setIfAbsent(buildKey(idempotencyKey), paymentId, DEFAULT_TTL);
        return Boolean.TRUE.equals(stored);
    }

    /**
     * Explicitly evict — called when a payment is permanently deleted (GDPR, admin ops).
     */
    public void evict(String idempotencyKey) {
        redisTemplate.delete(buildKey(idempotencyKey));
        log.debug("Evicted idempotency cache entry for key: {}", idempotencyKey);
    }

    private String buildKey(String idempotencyKey) {
        return KEY_PREFIX + idempotencyKey;
    }
}
