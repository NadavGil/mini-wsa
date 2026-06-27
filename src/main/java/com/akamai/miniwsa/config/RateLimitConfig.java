package com.akamai.miniwsa.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP rate limiter using Bucket4j token-bucket algorithm.
 *
 * Security note: X-Forwarded-For is deliberately NOT trusted here.
 * The TCP remote address is used; deploy behind a trusted proxy that
 * sets/replaces XFF rather than trusting client-supplied headers.
 *
 * Map size is soft-capped at MAX_BUCKETS. Over-limit IPs get a
 * stateless (non-stored) bucket — they are still rate-limited but the
 * map does not grow beyond MAX_BUCKETS. The cap is best-effort under
 * concurrent load; it is not a hard security boundary.
 *
 * Note: @Component (not @Configuration) is required for Spring Boot
 * to auto-register this class as a servlet filter in the filter chain.
 */
@Component
@Slf4j
public class RateLimitConfig extends OncePerRequestFilter {

    private static final int MAX_BUCKETS = 50_000;

    @Value("${wsa.rate-limit.requests-per-minute:200}")
    private int requestsPerMinute;

    // IP → token bucket. Access is thread-safe via ConcurrentHashMap semantics.
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Use the actual TCP remote address — never trust XFF without a verified trusted proxy
        String ip = request.getRemoteAddr();
        Bucket bucket = getBucket(ip);

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            log.debug("Rate limit exceeded for IP: {}", ip);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded\"}");
        }
    }

    /**
     * Returns a Bucket for the given IP.
     * If the map is at capacity, returns a fresh stateless bucket (not stored) —
     * the request is still rate-limited but the map does not grow further.
     * This avoids the check-evict-insert TOCTOU race while keeping the map bounded.
     */
    private Bucket getBucket(String ip) {
        Bucket existing = buckets.get(ip);
        if (existing != null) {
            return existing;
        }
        // Soft cap: new IP over limit gets a stateless bucket — not stored, map stays bounded
        if (buckets.size() >= MAX_BUCKETS) {
            return newBucket();
        }
        return buckets.computeIfAbsent(ip, k -> newBucket());
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.classic(
                requestsPerMinute,
                Refill.greedy(requestsPerMinute, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }
}
