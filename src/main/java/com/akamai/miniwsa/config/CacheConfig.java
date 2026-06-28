package com.akamai.miniwsa.config;

import com.akamai.miniwsa.enrichment.InMemoryRepeatOffenderCache;
import com.akamai.miniwsa.enrichment.RedisRepeatOffenderCache;
import com.akamai.miniwsa.enrichment.RepeatOffenderCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

/**
 * IoC wiring for {@link RepeatOffenderCache}.
 *
 * <p>Two implementations are available, selected by {@code wsa.cache.redis.enabled}:
 *
 * <table>
 *   <tr><th>Property</th><th>Implementation</th><th>Suitable for</th></tr>
 *   <tr><td>{@code false} (default)</td><td>{@link InMemoryRepeatOffenderCache}</td>
 *       <td>Dev, single-instance staging</td></tr>
 *   <tr><td>{@code true}</td><td>{@link RedisRepeatOffenderCache}</td>
 *       <td>Production, multi-instance deployment</td></tr>
 * </table>
 *
 * <p>Swapping implementations requires only a config change — no service or
 * controller code changes (DAL abstraction via interface).
 */
@Configuration
@EnableScheduling
public class CacheConfig {

    /**
     * Redis-backed cache — activated when {@code wsa.cache.redis.enabled=true}.
     * Requires a {@link StringRedisTemplate} bean (auto-configured by
     * {@code spring-boot-starter-data-redis} when {@code spring.data.redis.host} is set).
     */
    @Bean
    @ConditionalOnProperty(name = "wsa.cache.redis.enabled", havingValue = "true")
    public RepeatOffenderCache redisRepeatOffenderCache(
            StringRedisTemplate redisTemplate,
            @Value("${wsa.cache.window-minutes:10}") int windowMinutes,
            @Value("${wsa.cache.repeat-offender-threshold:5}") int threshold) {
        return new RedisRepeatOffenderCache(redisTemplate, Duration.ofMinutes(windowMinutes), threshold);
    }

    /**
     * In-memory fallback — used when Redis is not enabled.
     * WARNING: does not work correctly with 2+ app instances; each instance maintains
     * its own view of attacker history. An attacker splitting requests across pods
     * will not be flagged. Enable Redis for production multi-instance deployments.
     */
    @Bean
    @ConditionalOnMissingBean(RepeatOffenderCache.class)
    public RepeatOffenderCache inMemoryRepeatOffenderCache(
            @Value("${wsa.cache.window-minutes:10}") int windowMinutes,
            @Value("${wsa.cache.repeat-offender-threshold:5}") int threshold,
            @Value("${wsa.cache.max-ip-entries:10000}") int maxEntries) {
        return new InMemoryRepeatOffenderCache(windowMinutes, threshold, maxEntries);
    }
}
