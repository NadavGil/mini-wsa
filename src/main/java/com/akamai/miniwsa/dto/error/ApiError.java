package com.akamai.miniwsa.dto.error;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        List<FieldViolation> violations
) {
    public record FieldViolation(String field, String reason) {}
}
