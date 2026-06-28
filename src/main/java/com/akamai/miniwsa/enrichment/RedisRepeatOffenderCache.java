package com.akamai.miniwsa.enrichment;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Redis-backed repeat-offender cache using a sorted set per client IP.
 *
 * <p>This implementation is horizontally scalable: all app instances share the
 * same Redis cluster, so an attacker hitting multiple pods is correctly flagged.
 * The in-memory implementation ({@link InMemoryRepeatOffenderCache}) silently
 * degrades threat detection when more than one instance is running.
 *
 * <h3>Data model</h3>
 * <pre>
 * Key:    "wsa:ro:{clientIp}"
 * Value:  sorted set — score=epoch_ms, member="{epochMs}:{uuid}"
 * TTL:    window duration (auto-expires stale keys)
 * </pre>
 *
 * <h3>Operations</h3>
 * <ul>
 *   <li>{@code isRepeatOffender}: {@code ZCOUNT key (now-window) now} — O(log N)</li>
 *   <li>{@code record}: {@code ZADD + ZREMRANGEBYSCORE + EXPIRE} — O(log N)</li>
 * </ul>
 *
 * <p>Activated by setting {@code wsa.cache.redis.enabled=true} in application config.
 * Requires a Redis connection configured via {@code spring.data.redis.*}.
 * Instantiated by {@link com.akamai.miniwsa.config.CacheConfig} — not a {@code @Component}.
 */
public class RedisRepeatOffenderCache implements RepeatOffenderCache {

    private static final String KEY_PREFIX = "wsa:ro:";

    private final StringRedisTemplate redis;
    private final Duration window;
    private final int threshold;

    public RedisRepeatOffenderCache(StringRedisTemplate redis, Duration window, int threshold) {
        this.redis = redis;
        this.window = window;
        this.threshold = threshold;
    }

    /**
     * Returns true if there are {@code >= threshold} events from this IP within the window.
     *
     * <p>Uses Redis {@code ZCOUNT} over the epoch-ms score range. This is O(log N) and
     * does not load the members into Java heap — safe for high-volume IPs.
     */
    @Override
    public boolean isRepeatOffender(String clientIp, Instant eventTime) {
        String key = KEY_PREFIX + clientIp;
        long windowStartMs = eventTime.minus(window).toEpochMilli();
        long nowMs = eventTime.toEpochMilli();

        Long count = redis.opsForZSet().count(key, windowStartMs, nowMs);
        return count != null && count >= threshold;
    }

    /**
     * Records an event from {@code clientIp} at {@code eventTime}.
     *
     * <ol>
     *   <li>ZADD: adds the event with score = epoch ms (UUID suffix guarantees member uniqueness).</li>
     *   <li>ZREMRANGEBYSCORE: prunes entries older than the window boundary.</li>
     *   <li>EXPIRE: refreshes the key TTL so idle IPs don't accumulate stale keys.</li>
     * </ol>
     */
    @Override
    public void record(String clientIp, Instant eventTime) {
        String key = KEY_PREFIX + clientIp;
        long epochMs = eventTime.toEpochMilli();
        // UUID suffix ensures uniqueness — two events at the same millisecond don't collide.
        String member = epochMs + ":" + UUID.randomUUID();

        redis.opsForZSet().add(key, member, epochMs);

        // Prune entries outside the sliding window
        long cutoffMs = eventTime.minus(window).toEpochMilli();
        redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoffMs);

        // Extend TTL so the key expires naturally when the IP goes quiet
        redis.expire(key, window.plusMinutes(1));
    }
}
