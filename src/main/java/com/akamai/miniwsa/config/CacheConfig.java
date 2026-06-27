package com.akamai.miniwsa.config;

import com.akamai.miniwsa.enrichment.InMemoryRepeatOffenderCache;
import com.akamai.miniwsa.enrichment.RepeatOffenderCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * IoC wiring for the RepeatOffenderCache.
 * Swap this bean for a Redis-backed implementation by changing this config —
 * no other code changes required (DAL abstraction via interface).
 */
@Configuration
@EnableScheduling
public class CacheConfig {

    @Bean
    public RepeatOffenderCache repeatOffenderCache(
            @Value("${wsa.cache.window-minutes:10}") int windowMinutes,
            @Value("${wsa.cache.repeat-offender-threshold:5}") int threshold,
            @Value("${wsa.cache.max-ip-entries:10000}") int maxEntries) {
        return new InMemoryRepeatOffenderCache(windowMinutes, threshold, maxEntries);
    }
}
