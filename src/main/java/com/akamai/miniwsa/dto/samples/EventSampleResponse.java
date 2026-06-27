package com.akamai.miniwsa.dto.samples;

import com.akamai.miniwsa.domain.ActionType;
import com.akamai.miniwsa.domain.AttackCategory;

import java.time.Instant;

public record EventSampleResponse(
        String eventId,
        Instant timestamp,
        Long configId,
        String clientIp,
        String path,
        String method,
        Integer statusCode,
        AttackCategory category,
        ActionType action,
        String attackType,
        int threatScore,
        boolean repeatOffender,
        String country
) {}
