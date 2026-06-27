package com.akamai.miniwsa.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP rate limiter using Bucket4j token-bucket algorithm.
 *
 * Security note: X-Forwarded-For is deliberately NOT trusted here.
 * The remote address reported by Tomcat is used because this service is
 * intended to sit behind a load balancer that strips/replaces XFF.
 * If deployed behind a trusted proxy, configure Spring's RemoteIpFilter
 * instead of reading XFF headers directly (prevents IP spoofing attacks).
 *
 * The bucket map is capped at MAX_BUCKETS entries to prevent OOM from
 * IP-exhaustion (unbounded map) attacks.
 */
@Configuration
public class RateLimitConfig extends OncePerRequestFilter {

    private static final int MAX_BUCKETS = 50_000;

    @Value("${wsa.rate-limit.requests-per-minute:200}")
    private int requestsPerMinute;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Use the actual TCP remote address — never trust XFF without a trusted proxy
        String ip = request.getRemoteAddr();
        Bucket bucket = getBucket(ip);

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded\"}");
        }
    }

    private Bucket getBucket(String ip) {
        Bucket existing = buckets.get(ip);
        if (existing != null) {
            return existing;
        }
        // Cap map size to prevent OOM under IP-exhaustion attack
        if (buckets.size() >= MAX_BUCKETS) {
            String toEvict = buckets.keySet().iterator().next();
            buckets.remove(toEvict);
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
