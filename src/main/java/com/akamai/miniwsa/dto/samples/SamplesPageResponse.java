package com.akamai.miniwsa.dto.samples;

import java.util.List;

/**
 * Paginated response for event samples.
 *
 * <p>Uses page-number semantics (0-indexed) rather than row-offset semantics.
 * Row-offset pagination ({@code OFFSET N LIMIT M}) causes full table scans at
 * depth and returns incorrect windows for non-aligned offsets when mapped to
 * JPA {@code PageRequest}. At production scale, replace with keyset pagination
 * ({@code WHERE timestamp < :cursor ORDER BY timestamp DESC}).
 */
public record SamplesPageResponse(long total, int limit, int page, List<EventSampleResponse> events) {}
