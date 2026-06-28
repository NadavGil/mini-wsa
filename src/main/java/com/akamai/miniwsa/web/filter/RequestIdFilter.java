package com.akamai.miniwsa.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Injects a unique {@code requestId} into the SLF4J MDC for every request.
 *
 * <p>This makes every log line emitted during a request traceable to a single
 * inbound call — critical for debugging production incidents where multiple
 * requests interleave in the log stream. The ID is also echoed in the
 * {@code X-Request-Id} response header so callers can correlate their own
 * logs with server-side log entries.
 *
 * <p>Ordered first ({@code Integer.MIN_VALUE}) so the requestId is in MDC
 * before any downstream filter or security layer logs anything.
 */
@Component
@Order(Integer.MIN_VALUE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_KEY = "requestId";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Honour a caller-supplied ID (e.g. from an upstream API gateway or load balancer)
        // so end-to-end trace IDs propagate through the system.
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(REQUEST_ID_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // CRITICAL: always clear MDC after the request to prevent leakage
            // into the next request served by the same thread-pool thread.
            MDC.remove(REQUEST_ID_KEY);
        }
    }
}
